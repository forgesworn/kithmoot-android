package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.media.NodeCallLease
import dev.forgesworn.kithmoot.protocol.NodeRooms
import org.webrtc.PeerConnection
import kotlin.test.*

class NodeCallLeaseTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val device = LocalSigner(ByteArray(32) { 4 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), "ee".repeat(32), "https://node.example", service.pubkey, now).toJson(), now))
    private fun login(end: Long = now + 300, urls: List<String> = node.turnUrls) = NodeTurnLogin(urls, "$end:${"ab".repeat(12)}", "a".repeat(27) + "=", end, now + 3600)
    @Test fun `native configuration accepts only its current own node TURN login in relay only mode`() {
        val lease = NodeCallLease(node, device.pubkey, { true }, { now }, { 0 })
        lease.install(login()); val servers = lease.servers()
        lease.assertConfiguration(node, device.pubkey, servers, true)
        assertEquals(node.turnUrls, servers.single().urls)
        assertFailsWith<IllegalArgumentException> { lease.assertConfiguration(node, device.pubkey, servers, false) }
        assertFailsWith<IllegalArgumentException> { lease.assertConfiguration(node, root.pubkey, servers, true) }
        assertFailsWith<IllegalArgumentException> { lease.assertConfiguration(node, device.pubkey, servers + PeerConnection.IceServer.builder("stun:stun.example:3478").createIceServer(), true) }
        assertFailsWith<IllegalArgumentException> { lease.install(login(urls = listOf("turns:other.example:443?transport=tcp"))) }
    }
    @Test fun `wall clock rollback never extends the monotonic cached login ceiling`() {
        var wall = now; var ticks = 0L
        val lease = NodeCallLease(node, device.pubkey, { true }, { wall }, { ticks })
        lease.install(login()); assertTrue(lease.permitted())
        wall -= 600; ticks = 300_000_000_000L
        assertFalse(lease.permitted()); assertFailsWith<IllegalStateException> { lease.servers() }
    }
    @Test fun `reinstalling a cached login after a clock change cannot restart its five minute ceiling`() {
        var wall = now; var ticks = 0L
        val lease = NodeCallLease(node, device.pubkey, { true }, { wall }, { ticks })
        val cached = NodeTurnLogin(node.turnUrls, "${now + 300}:${"ab".repeat(12)}", "a".repeat(27) + "=", now + 300, now + 3600, now)
        lease.install(cached)
        ticks = 240_000_000_000L; wall = now + 230
        lease.install(cached)
        ticks = 300_000_000_000L; wall = now + 290
        assertFalse(lease.permitted())
        assertFailsWith<IllegalStateException> { lease.install(cached) }
        val fresh = NodeTurnLogin(node.turnUrls, "${wall + 300}:${"ab".repeat(12)}", "a".repeat(27) + "=", wall + 300, now + 3600, wall)
        lease.install(fresh); assertTrue(lease.permitted())
    }
    @Test fun `issuer clock skew does not extend five minute monotonic use or accept overlong issued lifetimes`() {
        var ticks = 0L
        val lease = NodeCallLease(node, device.pubkey, { true }, { now }, { ticks })
        fun issued(at: Long, end: Long) = NodeTurnLogin(node.turnUrls, "$end:${"ab".repeat(12)}", "a".repeat(27) + "=", end, now + 3600, at)
        lease.install(issued(now + 1, now + 301)); assertTrue(lease.permitted())
        ticks = 300_000_000_000L; assertFalse(lease.permitted())
        assertFailsWith<IllegalArgumentException> { lease.install(issued(now + 31, now + 331)) }
        assertFailsWith<IllegalArgumentException> { lease.install(issued(now, now + 301)) }
    }
    @Test fun `withdrawal and close clear the capability and an overlong credential cannot install`() {
        var allowed = true
        val lease = NodeCallLease(node, device.pubkey, { allowed }, { now }, { 0 })
        assertFailsWith<IllegalArgumentException> { lease.install(login(now + 301)) }
        lease.install(login()); allowed = false; assertFalse(lease.permitted())
        allowed = true; lease.close(); assertFalse(lease.permitted()); assertFailsWith<IllegalStateException> { lease.install(login()) }
    }
}
