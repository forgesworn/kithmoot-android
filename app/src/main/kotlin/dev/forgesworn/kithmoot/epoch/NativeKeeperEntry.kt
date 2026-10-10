package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Acquires durable authority before opening any route. Owns no transport and
 * exposes no signer. Call open/stop on IO; close withdraws a started owner now. */
internal class NativeKeeperEntry private constructor(private val source: NativeKeeperJournal,
    private val receiver: EpochVault, private val ledger: RoomRekeyLedger) : AutoCloseable {
    val binding get() = source.binding
    private var controller: NativeKeeperController? = null
    private val gate = Mutex()
    @Volatile private var closed = false

    suspend fun start(live: RoomSession, endpoints: NativeKeeperEndpoints, parent: CoroutineScope,
        stillSelected: () -> Boolean, dispatcher: CoroutineDispatcher = Dispatchers.IO): NativeKeeperController = gate.withLock {
        check(!closed && controller == null)
        live.holdKeeperStartup()
        val owner = NativeKeeperController.start(source, receiver, live, ledger, endpoints, parent,
            { !closed && stillSelected() }, dispatcher)
        controller = owner
        if (closed) { owner.stop(); error("Native host was withdrawn during startup") }
        owner
    }

    override fun close() { closed = true; controller?.close() }
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        close()
        gate.withLock { controller?.stop() ?: run { try { ledger.closeIfUnbound() } finally { source.close() } } }
    }

    companion object {
        fun open(room: SavedRoom, receiver: EpochVault, openSource: () -> NativeKeeperJournal,
            openLedger: (RoomRekeyBinding, Boolean) -> RoomRekeyLedger): NativeKeeperEntry {
            val source = openSource()
            var ledger: RoomRekeyLedger? = null
            try {
                room.verifyNativeAuthority(source)
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
                return NativeKeeperEntry(source, receiver, ledger)
            } catch (error: Exception) {
                try { ledger?.closeIfUnbound() } finally { source.close() }
                throw error
            }
        }
    }
}
