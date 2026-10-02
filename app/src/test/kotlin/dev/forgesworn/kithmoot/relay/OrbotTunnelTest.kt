package dev.forgesworn.kithmoot.relay

import okhttp3.OkHttpClient
import org.bouncycastle.crypto.digests.SHA3Digest
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a local Orbot proxy sees. The 29 September anonymous run found a
 * `ws://` onion relay sent to Orbot as plain proxied GETs, which never reached
 * the onion; every Tor-only relay must arrive at the proxy as a CONNECT to the
 * onion, whatever its scheme.
 */
class OrbotTunnelTest {
    private val onion: String = run {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val key = ByteArray(32) { (it + 1).toByte() }
        val input = ".onion checksum".toByteArray(Charsets.US_ASCII) + key + byteArrayOf(3)
        val digest = ByteArray(32)
        SHA3Digest(256).apply { update(input, 0, input.size); doFinal(digest, 0) }
        val bytes = key + byteArrayOf(digest[0], digest[1], 3)
        var bits = 0; var value = 0
        val out = StringBuilder()
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; out.append(alphabet[(value ushr bits) and 31]) }
        }
        "$out.onion"
    }

    /** A stand-in for Orbot's HTTP proxy: records each request line, then tunnels to a one-message relay. */
    private class FakeOrbot(private val answer: String = "HTTP/1.1 200 Connection established") {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val requestLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(socket) }
            }
        }

        private fun serve(socket: Socket) = socket.use {
            val input = it.getInputStream()
            val head = readHead(input)
            requestLines += head.first()
            if (!head.first().startsWith("CONNECT ")) return@use
            it.getOutputStream().write("$answer\r\n\r\n".toByteArray())
            if (!answer.contains(" 200 ")) return@use
            val upgrade = readHead(input)
            val key = upgrade.first { line -> line.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            val out = it.getOutputStream()
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            val text = "hello".toByteArray()
            out.write(byteArrayOf(0x81.toByte(), text.size.toByte()) + text)
            out.flush()
            runCatching { input.read() }
        }

        private fun readHead(input: InputStream): List<String> {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    val done = line.toString().trimEnd('\r')
                    if (done.isEmpty()) break
                    lines += done; line.clear()
                } else line.append(b.toChar())
            }
            return lines
        }

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)))
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

        fun close() { server.close(); worker.join(1000) }
    }

    private val proxies = mutableListOf<FakeOrbot>()
    private fun orbot(answer: String = "HTTP/1.1 200 Connection established") = FakeOrbot(answer).also { proxies += it }
    @AfterTest fun closeProxies() = proxies.forEach { it.close() }

    private class Heard : RelaySocketListener {
        val opened = CountDownLatch(1)
        val message = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var text: String? = null
        @Volatile var reason: String? = null
        override fun onOpen() = opened.countDown()
        override fun onMessage(text: String) { this.text = text; message.countDown() }
        override fun onClosed(reason: String) { this.reason = reason; closed.countDown() }
    }

    @Test fun `a ws onion relay is tunnelled to the onion with CONNECT and opens`() {
        val proxy = orbot()
        val heard = Heard()
        val socket = OrbotTorRelaySockets(proxy.client()).open("ws://$onion/", heard)
        assertTrue(heard.message.await(10, TimeUnit.SECONDS), "relay never spoke: ${heard.reason}")
        assertEquals("hello", heard.text)
        assertEquals(listOf("CONNECT $onion:80 HTTP/1.1"), proxy.requestLines.toList())
        socket.close()
    }

    @Test fun `a ws onion relay on its own port is tunnelled to that port`() {
        val proxy = orbot()
        val heard = Heard()
        OrbotTorRelaySockets(proxy.client()).open("ws://$onion:7777", heard).also {
            assertTrue(heard.message.await(10, TimeUnit.SECONDS), "relay never spoke: ${heard.reason}")
            it.close()
        }
        assertEquals(listOf("CONNECT $onion:7777 HTTP/1.1"), proxy.requestLines.toList())
    }

    @Test fun `a wss onion relay still reaches the proxy as CONNECT`() {
        val proxy = orbot("HTTP/1.1 403 Forbidden")
        val heard = Heard()
        OrbotTorRelaySockets(proxy.client()).open("wss://$onion", heard)
        assertTrue(heard.closed.await(10, TimeUnit.SECONDS))
        assertTrue(proxy.requestLines.isNotEmpty())
        assertTrue(proxy.requestLines.all { it == "CONNECT $onion:443 HTTP/1.1" }, proxy.requestLines.toString())
    }

    @Test fun `a ws tunnel the proxy refuses fails and goes nowhere else`() {
        val proxy = orbot("HTTP/1.1 403 Forbidden")
        val heard = Heard()
        OrbotTorRelaySockets(proxy.client()).open("ws://$onion", heard)
        assertTrue(heard.closed.await(10, TimeUnit.SECONDS))
        assertEquals(1L, heard.opened.count, "opened despite the refusal")
        assertTrue(heard.reason.orEmpty().contains("Orbot refused the tunnel: HTTP/1.1 403"), heard.reason)
        assertTrue(proxy.requestLines.isNotEmpty())
        assertTrue(proxy.requestLines.all { it == "CONNECT $onion:80 HTTP/1.1" }, proxy.requestLines.toString())
    }

    @Test fun `the tunnel resolves only onion names, and never looks one up`() {
        val address = OrbotConnectTunnel.dns.lookup(onion).single()
        assertEquals(onion, address.hostName)
        assertEquals(listOf<Byte>(0, 0, 0, 0), address.address.toList())
        assertFailsWith<UnknownHostException> { OrbotConnectTunnel.dns.lookup("relay.example") }
        assertFailsWith<UnknownHostException> { OrbotConnectTunnel.dns.lookup("127.0.0.1") }
    }

    @Test fun `the tunnel socket dials nothing for a target that is not an onion`() {
        val proxy = orbot()
        val factory = OrbotConnectTunnel.plainClient(proxy.client()).socketFactory
        for (target in listOf(InetSocketAddress.createUnresolved("relay.example", 80), InetSocketAddress(InetAddress.getLoopbackAddress(), 80))) {
            val socket = factory.createSocket()
            assertFailsWith<IOException> { socket.connect(target, 1000) }
            assertFalse(socket.isConnected)
        }
        Thread.sleep(200)
        assertEquals(emptyList(), proxy.requestLines.toList())
        assertEquals(Proxy.NO_PROXY, OrbotConnectTunnel.plainClient(proxy.client()).proxy)
    }
}
