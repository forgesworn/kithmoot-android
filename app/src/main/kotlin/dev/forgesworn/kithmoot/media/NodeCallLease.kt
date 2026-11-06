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
    private data class PendingRoute(val from: String, val next: String, val current: () -> Boolean)
    @Volatile private var held: Held? = null
    @Volatile private var closed = false
    private var preferred: String? = null
    private var pendingRoute: PendingRoute? = null
    private var retryCurrentRoute = false
    @Synchronized internal fun install(login: NodeTurnLogin) {
        check(!closed && allowed())
        requireNotNull(NodeRooms.read(node.wire, now(), node.authority))
        require(login.urls.size in 1..4 && login.urls.distinct() == login.urls && login.urls.all { it in node.turnUrls }
            && login.validLifetime(now()) && login.accessExpiresAt >= login.expiresAt
            && login.accessExpiresAt <= (node.endsAt ?: Long.MAX_VALUE)
            && Regex("^[0-9]{1,12}:[a-f0-9]{24}$").matches(login.username) && login.username.substringBefore(':').toLong() == login.expiresAt
            && Regex("^[A-Za-z0-9+/]{27}=$").matches(login.credential))
        val at = monotonic()
        var remaining = minOf(300L, login.expiresAt - now()) * 1_000_000_000L
        // Repeating a cached issuer response cannot mint another five minutes.
        // Compare relative times so nanoTime wraparound remains well-defined.
        held?.takeIf { it.login.username == login.username }?.let { remaining = minOf(remaining, it.deadline - at) }
        check(remaining > 0) { "Cached node call credentials have expired" }
        held = Held(login, at + remaining)
        val pending = pendingRoute
        pendingRoute = null
        if (preferred !in login.urls) preferred = routes(login.urls).first()
        else if (!retryCurrentRoute && pending != null && pending.from == preferred && pending.current() && pending.next in login.urls) preferred = pending.next
        retryCurrentRoute = false
    }
    internal fun permitted(): Boolean {
        val value = held ?: return false
        return !closed && allowed() && value.login.expiresAt > now() && value.deadline - monotonic() > 0 && (node.endsAt?.let { now() < it } ?: true)
    }
    internal fun expiresAt(): Long = held?.login?.expiresAt ?: 0
    internal fun remainingMillis(): Long = held?.let {
        minOf((it.deadline - monotonic()) / 1_000_000, (it.login.expiresAt - now()) * 1000).coerceAtLeast(0)
    } ?: 0
    @Synchronized internal fun servers(): List<PeerConnection.IceServer> {
        check(permitted())
        val login = requireNotNull(held).login
        return listOf(PeerConnection.IceServer.builder(listOf(requireNotNull(preferred))).setUsername(login.username).setPassword(login.credential).createIceServer())
    }
    @Synchronized internal fun assertConfiguration(record: NodeRoomRecord, pubkey: String, servers: List<PeerConnection.IceServer>, relayOnly: Boolean) {
        check(permitted()) { "Node call access is unavailable" }
        require(record.event.id == node.event.id && pubkey == device && relayOnly && NodeEvidence.key.matches(pubkey))
        val login = requireNotNull(held).login
        require(servers.size == 1 && servers[0].urls == listOf(preferred) && servers[0].username == login.username && servers[0].password == login.credential) {
            "Node calls require only the current own-node TURN login"
        }
    }
    /** Coalesce a route request; only fresh authorised login installation commits it. */
    @Synchronized internal fun advanceRoute(from: String, stillCurrent: () -> Boolean = { true }): Boolean {
        if (!permitted()) return false
        val ordered = routes(requireNotNull(held).login.urls)
        if (from !in ordered) return false
        if (preferred != from) return true
        val next = ordered.getOrNull(ordered.indexOf(from) + 1) ?: return false
        pendingRoute = PendingRoute(from, next, stillCurrent)
        return true
    }
    /** Failures observed offline cannot select the route used after access returns. */
    @Synchronized internal fun accessUnavailable() {
        if (!closed) { pendingRoute = null; retryCurrentRoute = true }
    }
    /** An explicit network change gets a fresh attempt at the fastest route. */
    @Synchronized internal fun resetRoutes() {
        pendingRoute = null
        if (!closed) preferred = held?.login?.urls?.let { routes(it).first() }
    }
    private fun routes(urls: List<String>): List<String> = urls.sortedBy {
        when {
            it.startsWith("turn:") && it.endsWith("?transport=udp") -> 0
            it.startsWith("turns:") && it.contains(":443?") -> 1
            it.startsWith("turns:") -> 2
            else -> 3
        }
    }
    @Synchronized internal fun close() { closed = true; held = null; preferred = null; pendingRoute = null; retryCurrentRoute = false }
}
