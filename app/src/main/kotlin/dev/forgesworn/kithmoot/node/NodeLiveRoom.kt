package dev.forgesworn.kithmoot.node

import android.content.Context
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/** One native room's node capabilities and lifetime. Every adapter shares the
 * captured identity guard and the validated device access lease. */
internal class NodeLiveRoom private constructor(
    val node: NodeRoomRecord,
    val access: NodeDeviceAccess,
    val transport: NodeRoomTransport,
    val keeper: NodeKeeperAdmissions?,
    val inbox: NodeKeeperInbox?,
    val epochDesk: NodeRootEpochDesk?,
    private val device: LocalSigner,
    private val deviceKey: ByteArray,
    private val client: NodeClient,
    private val scope: CoroutineScope,
    private val onUnavailable: () -> Unit,
    private val now: () -> Long,
    private val store: NodeStore,
    private val vault: EpochVault,
    private val participant: String,
    private val ledger: RoomRekeyLedger?,
    private val allowed: () -> Boolean,
) {
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    fun permitted() = !closed.get() && !paused.get() && access.permitted()
    fun pause() { paused.set(true); inbox?.close(); courier?.close(); transport.close(); onUnavailable() }
    private var courier: RoomRekeyCourier? = null
    var rekeys: NodeKeeperRekeys? = null
        private set
    suspend fun start() {
        check(permitted()); transport.start()
        if (ledger != null) {
            val source = RoomRekeyCourier.startAuthority(ledger, NativeKeeperEndpoints(ledger.binding, null, transport.observationPool), scope, ::permitted)
            courier = source
            rekeys = NodeKeeperRekeys(node, participant, store, requireNotNull(keeper), vault, source, allowed, now)
            rekeys?.retry()
        }
        access.start(scope); inbox?.start()
    }
    suspend fun call(context: Context, session: RoomSession): NodeManagedCall {
        check(permitted())
        return NodeManagedCall.create(context, session, device, access, client, scope, onUnavailable, now)
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        inbox?.close(); courier?.close(); ledger?.close(); transport.close(); access.close(); deviceKey.fill(0)
    }
    companion object {
        suspend fun open(saved: NodeCustody, store: NodeStore, vault: EpochVault, sockets: RelaySocketFactory,
            scope: CoroutineScope, allowed: () -> Boolean, onUnavailable: () -> Unit,
            onRemoved: suspend () -> Unit, onClosed: suspend () -> Unit,
            now: () -> Long = { System.currentTimeMillis() / 1000 }, context: Context): NodeLiveRoom {
            val node = saved.node
            require(saved.phase == "admitted" && saved.pendingClose == null)
            requireNotNull(NodeRooms.read(node.wire, now(), node.authority))
            val key = saved.deviceKey
            val device = LocalSigner(key)
            val client = NodeClient(now = now, allowed = allowed)
            val access = NodeDeviceAccess(node, saved.participant, saved.device, store,
                { grant -> client.access(node, grant, device) }, allowed, onUnavailable, onRemoved, onClosed, now)
            try {
                require(access.refresh() is NodeAccess.Active)
                val transport = NodeRoomTransport.create(node, device, NodeDoor.ROOM, sockets, scope, now, access::permitted)
                val keeper = if (saved.wire["rootKey"] == null) null else NodeKeeperAdmissions(node, saved.participant,
                    store, { event, signer -> client.postGrant(node, event, signer) }, allowed, now)
                val inbox = keeper?.let { NodeKeeperInbox(node, saved.participant, store, it, vault, sockets, scope, allowed, now) }
                val desk = keeper?.let { NodeRootEpochDesk(node, saved.participant, store, vault, allowed, now) }
                val ledger = if (keeper == null) null else {
                    val binding = RoomRekeyBinding(node.room, node.authority, saved.device, null, listOf(node.relay), dev.forgesworn.kithmoot.relay.RoomRoute.INTERNET)
                    val held = requireNotNull(store.custody(saved.participant, node.room))
                    val journal = dev.forgesworn.kithmoot.storage.RoomRekeyVault(context, binding).open(initialise = held.wire["rekeyJournal"] == null)
                    try { store.update(saved.participant, node.room) { it.changed { put("rekeyJournal", true) } }; journal }
                    catch (error: Throwable) { journal.close(); throw error }
                }
                return NodeLiveRoom(node, access, transport, keeper, inbox, desk, device, key, client, scope, onUnavailable, now,
                    store, vault, saved.participant, ledger, allowed)
            } catch (error: Throwable) { access.close(); key.fill(0); throw error }
        }
    }
}
