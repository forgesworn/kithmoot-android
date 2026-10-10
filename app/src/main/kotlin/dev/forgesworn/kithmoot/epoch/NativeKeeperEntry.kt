package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.storage.RoomRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Acquires durable authority before opening any route. Owns no transport and
 * exposes no signer. Call open/stop on IO; close withdraws a started owner now. */
internal class NativeKeeperEntry private constructor(private val source: NativeKeeperJournal,
    private val receiver: EpochVault, private val ledger: RoomRekeyLedger,
    private val rooms: RoomRepository? = null) : AutoCloseable {
    val binding get() = source.binding
    @Volatile private var controller: NativeKeeperController? = null
    @Volatile private var live: RoomSession? = null
    private val gate = Mutex()
    @Volatile private var closed = false

    /** The displayed capability comes from the actual index, checked against
     * this selected source and observation; never a captured pre-replacement URL. */
    fun sharingRoom(expected: NativeHostingState): SavedRoom? {
        val owner = controller ?: return null
        if (closed || !owner.canShareObservedInvitation(expected)) return null
        val index = rooms ?: return null
        return index.withNativeIndex(source) { room ->
            room.verifyNativeAuthority(source)
            room.takeIf { !closed && owner.canShareObservedInvitation(expected) }
        }
    }

    suspend fun start(live: RoomSession, endpoints: NativeKeeperEndpoints, parent: CoroutineScope,
        stillSelected: () -> Boolean, dispatcher: CoroutineDispatcher = Dispatchers.IO): NativeKeeperController = gate.withLock {
        check(!closed && controller == null)
        this.live = live
        live.holdKeeperStartup()
        val owner = if (rooms == null) NativeKeeperController.start(source, receiver, live, ledger, endpoints, parent,
            { !closed && stillSelected() }, dispatcher) else NativeKeeperController.startForRoom(source, receiver, live, ledger,
            endpoints, parent, { !closed && stillSelected() }, rooms, dispatcher)
        controller = owner
        if (closed) { owner.stop(); error("Native host was withdrawn during startup") }
        owner
    }

    override fun close() { closed = true; live?.holdKeeperStartup(); controller?.close() }
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        close()
        gate.withLock { controller?.stop() ?: run { try { ledger.closeIfUnbound() } finally { source.close() } } }
    }

    companion object {
        fun open(room: SavedRoom, receiver: EpochVault, openSource: () -> NativeKeeperJournal,
            openLedger: (RoomRekeyBinding, Boolean) -> RoomRekeyLedger): NativeKeeperEntry =
            openWithIndex(room, receiver, openSource, openLedger, null)
        fun openForRoom(room: SavedRoom, receiver: EpochVault, rooms: RoomRepository, openSource: () -> NativeKeeperJournal,
            openLedger: (RoomRekeyBinding, Boolean) -> RoomRekeyLedger): NativeKeeperEntry =
            openWithIndex(room, receiver, openSource, openLedger, rooms)
        private fun openWithIndex(room: SavedRoom, receiver: EpochVault, openSource: () -> NativeKeeperJournal,
            openLedger: (RoomRekeyBinding, Boolean) -> RoomRekeyLedger, rooms: RoomRepository?): NativeKeeperEntry {
            val source = openSource()
            var ledger: RoomRekeyLedger? = null
            try {
                if (source.snapshot().replacement == null) room.verifyNativeAuthority(source)
                else {
                    requireNotNull(rooms).withNativeIndex(source) { source.requirePendingIndex(it) }
                    source.requirePendingIndex(room)
                }
                val b = source.binding
                if (receiver.get(b.room) == null) {
                    check(source.mayInitialiseReceiver()) { "The native host's receiver state is missing; recovery is required" }
                    val epoch = source.epoch()
                    try { receiver.initialise(b.room, b.authority, epoch.secret, source.snapshot().high) }
                    finally { epoch.secret.fill(0) }
                }
                val firstCourier = !source.courierReady()
                if (firstCourier) check(source.mayInitialiseReceiver()) { "The native host has no initial courier marker; explicit migration is required" }
                val q = RoomRekeyBinding(b.room, b.authority, b.device, b.meshScope, b.relays, b.route)
                ledger = openLedger(q, firstCourier)
                if (firstCourier) source.recordCourierCreated(ledger)
                if (source.snapshot().replacement != null) {
                    // Validate existing marked stores BEFORE opening a route.
                    source.verifyPendingReplacementStores(receiver, ledger)
                    if (source.snapshot().replacement?.stage == NativeKeeperJournal.ReplacementStage.INDEX_VERIFIED)
                        source.reconcileReplacementIndex(requireNotNull(rooms), receiver, ledger)
                }
                return NativeKeeperEntry(source, receiver, ledger, rooms)
            } catch (error: Exception) {
                try { ledger?.closeIfUnbound() } finally { source.close() }
                throw error
            }
        }
    }
}
