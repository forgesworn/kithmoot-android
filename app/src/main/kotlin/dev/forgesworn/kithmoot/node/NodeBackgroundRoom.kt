package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

/** Background delivery uses the same device lease and sole relay as an open
 * node room. It never enters a shared public pool or launches an account signer. */
internal class NodeBackgroundRoom private constructor(
    val transport: NodeRoomTransport,
    private val access: NodeDeviceAccess,
    private val deviceKey: ByteArray,
    private val scope: CoroutineScope,
    private val inbox: NodeKeeperInbox?,
    private val epochDesk: NodeRootEpochDesk?,
    private val onUnavailable: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    @Volatile var failed = false
        private set
    fun permitted() = !closed.get() && !failed && access.permitted()
    private var deskJob: Job? = null
    private fun unavailable() {
        if (closed.get() || failed) return
        failed = true; inbox?.close(); deskJob?.cancel(); transport.close(); onUnavailable()
    }
    fun start() {
        check(permitted())
        transport.start(); access.start(scope); inbox?.start()
        epochDesk?.let { desk -> deskJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST), tags = mapOf("#d" to listOf(access.node.room), "#p" to listOf(access.node.authority))))).collect { request ->
                try { desk.answer(request)?.let(transport::publishRecovery) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A paused keeper never answers with an older key. */ }
            }
        } }
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        inbox?.close(); deskJob?.cancel(); transport.close(); access.close(); scope.cancel(); deviceKey.fill(0)
    }
    companion object {
        suspend fun open(saved: NodeCustody, store: NodeStore, vault: EpochVault, sockets: RelaySocketFactory,
            parent: CoroutineScope, allowed: () -> Boolean, onUnavailable: () -> Unit,
            onRemoved: suspend () -> Unit, onClosed: suspend () -> Unit,
            now: () -> Long = { System.currentTimeMillis() / 1000 },
            query: (suspend (String, LocalSigner) -> NodeAccess)? = null,
            blocked: (String) -> Boolean = { false }): NodeBackgroundRoom {
            require(saved.phase == "admitted" && saved.pendingClose == null)
            val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
            val key = saved.deviceKey; val signer = LocalSigner(key)
            var result: NodeBackgroundRoom? = null
            val client = NodeClient(now = now, allowed = allowed)
            val access = NodeDeviceAccess(saved.node, saved.participant, saved.device, store,
                { grant -> query?.invoke(grant, signer) ?: client.access(saved.node, grant, signer) }, allowed, { result?.unavailable() }, onRemoved, onClosed, now)
            try {
                require(access.refresh() is NodeAccess.Active)
                val transport = NodeRoomTransport.create(saved.node, signer, NodeDoor.ROOM, sockets, scope, now, access::permitted)
                val keeper = if (saved.wire["rootKey"] == null) null else NodeKeeperAdmissions(saved.node, saved.participant, store,
                    { event, root -> client.postGrant(saved.node, event, root) }, access::permitted, now)
                val inbox = keeper?.let { NodeKeeperInbox(saved.node, saved.participant, store, it, vault, sockets, scope, access::permitted, now, blocked = blocked) }
                val desk = keeper?.let { NodeRootEpochDesk(saved.node, saved.participant, store, vault, access::permitted, now) }
                return NodeBackgroundRoom(transport, access, key, scope, inbox, desk, onUnavailable).also { result = it }
            } catch (error: Throwable) { access.close(); key.fill(0); scope.cancel(); throw error }
        }
    }
}
