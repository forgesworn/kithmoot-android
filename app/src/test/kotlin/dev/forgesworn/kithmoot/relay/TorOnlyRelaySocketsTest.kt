package dev.forgesworn.kithmoot.relay

import org.bouncycastle.crypto.digests.SHA3Digest
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorOnlyRelaySocketsTest {
    private val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    private val prefix = ".onion checksum".toByteArray(Charsets.US_ASCII)

    private fun base32(bytes: ByteArray): String {
        var bits = 0
        var value = 0
        val output = StringBuilder()
        for (byte in bytes) {
            value = (value shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(alphabet[(value ushr bits) and 31])
            }
        }
        if (bits != 0) output.append(alphabet[(value shl (5 - bits)) and 31])
        return output.toString()
    }

    private fun onion(): String {
        val key = ByteArray(32) { (it + 1).toByte() }
        val input = prefix + key + byteArrayOf(3)
        val digest = ByteArray(32)
        SHA3Digest(256).apply { update(input, 0, input.size); doFinal(digest, 0) }
        return "${base32(key + digest.copyOfRange(0, 2) + byteArrayOf(3))}.onion"
    }

    @Test fun `validates a checksum exact v3 onion hostname`() {
        val onion = onion()
        assertEquals(onion, TorOnlyRelayUrls.assertV3OnionHostname(onion.uppercase()))
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.assertV3OnionHostname("x${onion.drop(1)}") }
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.assertV3OnionHostname("sub.$onion") }
    }

    @Test fun `normalises only onion websocket relays`() {
        val onion = onion()
        assertEquals("ws://$onion/relay", TorOnlyRelayUrls.normalise("ws://$onion/relay"))
        assertEquals("wss://$onion", TorOnlyRelayUrls.normalise("wss://$onion"))
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.normalise("wss://relay.example") }
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.normalise("https://$onion") }
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.normalise("wss://user:pass@$onion") }
    }

    @Test fun `holds rather than opening direct media or duplicate routes`() {
        val onion = onion()
        assertEquals(listOf("ws://$onion/a"), TorOnlyRelayUrls.assertRoomTransport(listOf("ws://$onion/a"), emptyList()))
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.assertRoomTransport(emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.assertRoomTransport(listOf("ws://$onion"), listOf("stun:stun.example:3478")) }
        assertFailsWith<IllegalArgumentException> { TorOnlyRelayUrls.assertRoomTransport(listOf("ws://$onion", "ws://$onion"), emptyList()) }
    }

    @Test fun `never lets a tor dialler see a clearnet URL`() {
        var opened: String? = null
        val sockets = TorOnlyRelaySockets(TorRelaySocketFactory { url, _ ->
            opened = url
            object : RelaySocket { override fun send(text: String) = Unit; override fun close() = Unit }
        })
        val onion = onion()
        sockets.open("ws://$onion", object : RelaySocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) = Unit
            override fun onClosed(reason: String) = Unit
        })
        assertEquals("ws://$onion", opened)
        assertFailsWith<IllegalArgumentException> { sockets.open("wss://relay.example", object : RelaySocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) = Unit
            override fun onClosed(reason: String) = Unit
        }) }
    }

    @Test fun `Orbot carrier has a fixed loopback HTTP proxy and no system fallback`() {
        val proxy = requireNotNull(OrbotTorRelaySockets.defaultClient().proxy)
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals(OrbotTorRelaySockets.ORBOT_PROXY_HOST, address.hostString)
        assertEquals(OrbotTorRelaySockets.ORBOT_HTTP_PROXY_PORT, address.port)
    }
}
