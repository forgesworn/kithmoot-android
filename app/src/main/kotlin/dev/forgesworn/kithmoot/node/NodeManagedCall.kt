package dev.forgesworn.kithmoot.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.media.NodeCallLease
import dev.forgesworn.kithmoot.media.WebRtcEngine
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Own-node TURN with fresh allocations on renewal and network change.
 * Refusal/expiry stops media; temporary failure keeps only the unexpired lease. */
internal class NodeManagedCall private constructor(val engine: WebRtcEngine, private val lease: NodeCallLease) {
    private var renewal: Job? = null
    private var deadline: Job? = null
    private var unregisterNetwork: (() -> Unit)? = null
    private var requestRecovery: ((String?) -> Unit)? = null
    internal fun recoverNetwork() { requestRecovery?.invoke(null) }
    private val closed = AtomicBoolean(false)
    private val shutdown = NodeCallShutdown(stop = {
        closed.set(true)
        unregisterNetwork?.invoke(); unregisterNetwork = null; requestRecovery = null
        renewal?.cancel(); deadline?.cancel(); lease.close(); engine.onNodeRecovery = null; engine.stop()
    }, release = engine::dispose)
    fun close() = shutdown.close()
    fun dispose() = shutdown.dispose()
    companion object {
        suspend fun create(context: Context, session: RoomSession, device: ParticipantSigner, access: NodeDeviceAccess, client: NodeClient,
            scope: CoroutineScope, onUnavailable: () -> Unit, now: () -> Long = { System.currentTimeMillis() / 1000 },
            reconnectSignalling: () -> Unit = {}, awaitSignalling: suspend () -> Boolean = { true }): NodeManagedCall {
            val node = requireNotNull(session.nodeRoom)
            require(device.pubkey == session.identity.devicePubkey && access.node.event.id == node.event.id)
            val active = retryNodeRead { access.refresh() as? NodeAccess.Active ?: throw NodeRequestException(403) }
            val login = retryNodeRead { client.turn(node, active, device) }
            val lease = NodeCallLease(node, device.pubkey, access::permitted, now)
            lease.install(login)
            val engine = try { WebRtcEngine(context, session, scope, lease.servers(), relayOnly = true, nodeLease = lease) }
                catch (error: Throwable) { lease.close(); throw error }
            val result = NodeManagedCall(engine, lease)
            val recovery = NodeCallRecovery(lease::permitted, lease::remainingMillis,
                refresh = {
                    val permission = retryNodeRead { access.refresh() as? NodeAccess.Active ?: throw NodeRequestException(403) }
                    lease.install(retryNodeRead { client.turn(node, permission, device) })
                }, replace = { peers, grace ->
                    if (!awaitSignalling()) throw NodeRequestException()
                    engine.replaceNodePeers(lease.servers(), peers, grace)
                }, terminate = { result.close(); onUnavailable() })
            fun request(peer: String? = null) {
                if (result.closed.get()) return
                if (peer == null) lease.resetRoutes()
                runCatching(reconnectSignalling)
                recovery.request(peer)
            }
            engine.onNodeRecovery = { request(it) }
            result.requestRecovery = { request(it) }
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            if (connectivity != null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    private var route: Network? = connectivity.activeNetwork
                    override fun onAvailable(network: Network) {
                        val previous = route; route = network
                        if (previous != network) request()
                    }
                    override fun onLost(network: Network) { if (route == network) { route = null; request() } }
                }
                try {
                    connectivity.registerDefaultNetworkCallback(callback)
                    result.unregisterNetwork = { runCatching { connectivity.unregisterNetworkCallback(callback) }; Unit }
                } catch (_: Exception) { /* Media failures still request recovery if the platform watcher is unavailable. */ }
            }
            // Independent of a slow HTTP/signing operation: no request can keep
            // media running beyond the wall/monotonic credential deadline.
            result.deadline = scope.launch {
                while (isActive && !result.closed.get()) {
                    delay(250)
                    if (!lease.permitted()) { result.close(); onUnavailable(); break }
                }
            }
            result.renewal = scope.launch {
                try {
                    while (isActive && !result.closed.get()) {
                        delay(1000)
                        recovery.tick()
                    }
                } catch (cancelled: CancellationException) { result.close(); throw cancelled }
                catch (_: Exception) { result.close(); onUnavailable() }
            }
            return result
        }
    }
}
