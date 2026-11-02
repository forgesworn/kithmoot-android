package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.node.NodeEvidence
import dev.forgesworn.kithmoot.node.NodeTurnLogin
import dev.forgesworn.kithmoot.protocol.NodeRoomRecord
import dev.forgesworn.kithmoot.protocol.NodeRooms
import org.webrtc.PeerConnection

/** Native-media capability created only after node access and credential checks.
 * Both wall time and a monotonic five-minute ceiling bound a cached login. */
class NodeCallLease internal constructor(
    private val node: NodeRoomRecord,
    private val device: String,
    private val allowed: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val monotonic: () -> Long = System::nanoTime,
) {
    private data class Held(val login: NodeTurnLogin, val deadline: Long)
    @Volatile private var held: Held? = null
    @Volatile private var closed = false
    internal fun install(login: NodeTurnLogin) {
        check(!closed && allowed())
        requireNotNull(NodeRooms.read(node.wire, now(), node.authority))
        require(login.urls.size in 1..4 && login.urls.distinct() == login.urls && login.urls.all { it in node.turnUrls }
            && login.expiresAt > now() && login.expiresAt <= now() + 300 && login.accessExpiresAt >= login.expiresAt
            && login.accessExpiresAt <= (node.endsAt ?: Long.MAX_VALUE)
            && Regex("^[0-9]{1,12}:[a-f0-9]{24}$").matches(login.username) && login.username.substringBefore(':').toLong() == login.expiresAt
            && Regex("^[A-Za-z0-9+/]{27}=$").matches(login.credential))
        held = Held(login, monotonic() + minOf(300L, login.expiresAt - now()) * 1_000_000_000L)
    }
    internal fun permitted(): Boolean {
        val value = held ?: return false
        return !closed && allowed() && value.login.expiresAt > now() && value.deadline - monotonic() > 0 && (node.endsAt?.let { now() < it } ?: true)
    }
    internal fun expiresAt(): Long = held?.login?.expiresAt ?: 0
    internal fun servers(): List<PeerConnection.IceServer> {
        check(permitted())
        val login = requireNotNull(held).login
        return listOf(PeerConnection.IceServer.builder(login.urls).setUsername(login.username).setPassword(login.credential).createIceServer())
    }
    internal fun assertConfiguration(record: NodeRoomRecord, pubkey: String, servers: List<PeerConnection.IceServer>, relayOnly: Boolean) {
        check(permitted()) { "Node call access is unavailable" }
        require(record.event.id == node.event.id && pubkey == device && relayOnly && NodeEvidence.key.matches(pubkey))
        val login = requireNotNull(held).login
        require(servers.size == 1 && servers[0].urls == login.urls && servers[0].username == login.username && servers[0].password == login.credential) {
            "Node calls require only the current own-node TURN login"
        }
    }
    internal fun close() { closed = true; held = null }
}
