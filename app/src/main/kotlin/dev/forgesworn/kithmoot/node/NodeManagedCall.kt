package dev.forgesworn.kithmoot.node

import android.content.Context
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.media.NodeCallLease
import dev.forgesworn.kithmoot.media.WebRtcEngine
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Own-node TURN for every native peer, renewed before expiry. Any loss closes
 * native connections; no CallIceSelector, public issuer or hand-entered fallback. */
internal class NodeManagedCall private constructor(val engine: WebRtcEngine, private val lease: NodeCallLease) {
    private var renewal: Job? = null
    private val closed = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        renewal?.cancel(); lease.close(); engine.stop()
    }
    fun dispose() { close(); if (disposed.compareAndSet(false, true)) engine.dispose() }
    companion object {
        suspend fun create(context: Context, session: RoomSession, device: ParticipantSigner, access: NodeDeviceAccess, client: NodeClient,
            scope: CoroutineScope, onUnavailable: () -> Unit, now: () -> Long = { System.currentTimeMillis() / 1000 }): NodeManagedCall {
            val node = requireNotNull(session.nodeRoom)
            require(device.pubkey == session.identity.devicePubkey && access.node.event.id == node.event.id)
            val active = access.refresh() as? NodeAccess.Active ?: throw NodeRequestException(403)
            val login = client.turn(node, active, device)
            val lease = NodeCallLease(node, device.pubkey, access::permitted, now)
            lease.install(login)
            val engine = try { WebRtcEngine(context, session, scope, lease.servers(), relayOnly = true, nodeLease = lease) }
                catch (error: Throwable) { lease.close(); throw error }
            val result = NodeManagedCall(engine, lease)
            result.renewal = scope.launch {
                try {
                    while (isActive && !result.closed.get()) {
                        delay(1000)
                        check(lease.permitted())
                        if (lease.expiresAt() - now() <= 60) {
                            val permission = access.refresh() as? NodeAccess.Active ?: throw NodeRequestException(403)
                            lease.install(client.turn(node, permission, device))
                            engine.updateIceServers(lease.servers())
                        }
                    }
                } catch (cancelled: CancellationException) { result.close(); throw cancelled }
                catch (_: Exception) { result.close(); onUnavailable() }
            }
            return result
        }
    }
}
