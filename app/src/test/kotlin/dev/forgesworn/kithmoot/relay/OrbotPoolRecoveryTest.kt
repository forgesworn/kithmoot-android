package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual WSS adapter + pool, with owned loopback proxy/TLS relay stand-ins.
 * Diagnostic retry timing only; no real Tor or production-latency acceptance. */
class OrbotPoolRecoveryTest {
    // Deterministic valid v3 address; no live service is contacted.
    private val onion = "aebagbafaydqqcikbmga2dqpcaireeyuculbogazdinryhi6d4qcmeqd.onion"

    @Test fun wss_pool_recovers_after_504_on_a_fresh_connect_without_restart() = runBlocking {
        TorOnlyRelayUrls.assertV3OnionHostname(onion)
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(onion).build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val relay = MockWebServer()
        relay.useHttps(serverTls.sslSocketFactory(), false)
        relay.start(InetAddress.getByName("127.0.0.1"), 0)
        val proxy = RejectThenRestoreProxy(relay.port)
        val dnsCalls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
            .dns(Dns { hostname -> dnsCalls.incrementAndGet(); error("Unexpected application DNS: $hostname") })
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val opened = ConcurrentLinkedQueue<Int>()
        val messages = ConcurrentLinkedQueue<Int>()
        val attempts = AtomicInteger()
        val refused = CompletableDeferred<String>()
        val adapter = OrbotTorRelaySockets(client)
        // Observe real callbacks without replacing the adapter or its socket.
        val sockets = RelaySocketFactory { url, listener ->
            val attempt = attempts.incrementAndGet()
            adapter.open(url, object : RelaySocketListener {
                override fun onOpen() { opened += attempt; listener.onOpen() }
                override fun onMessage(text: String) { messages += attempt; listener.onMessage(text) }
                override fun onClosed(reason: String) {
                    listener.onClosed(reason)
                    if (attempt == 1) refused.complete(reason)
                }
            })
        }
        val url = "wss://$onion"
        val pool = RelayPool(listOf(url), sockets, scope,
            policy = TorCarrierTimings.policy.copy(baseDelayMs = 10, maxDelayMs = 20), random = Random(1))
        val event = NostrEvent(1, 1_800_000_000, emptyList(), "restored tunnel",
            "aa".repeat(32), "bb".repeat(32), "cc".repeat(64))
        val received = CompletableDeferred<NostrEvent>()
        val requestFrames = ConcurrentLinkedQueue<String>()
        val relayErrors = ConcurrentLinkedQueue<Throwable>()
        relay.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = Json.parseToJsonElement(text).jsonArray
                    if (frame[0].jsonPrimitive.content != "REQ") return
                    requestFrames += text
                    val id = frame[1].toString()
                    check(webSocket.send("[\"EVENT\",$id,${event.toCompactJson()}]"))
                } catch (error: Throwable) { relayErrors += error }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { relayErrors += t }
        }))
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pool.subscribe(listOf(Filter(kinds = listOf(1)))).collect { received.complete(it) }
        }
        try {
            withTimeout(10_000) {
                pool.start() // The only start; restoration changes the proxy, never the pool.
                assertTrue(refused.await().contains("504"), "The actual first adapter must report proxy refusal")
                assertTrue(proxy.firstClosed.await(2, TimeUnit.SECONDS), "Refused client socket was not closed")
                assertTrue(pool.connected.value.isEmpty())
                assertTrue(opened.isEmpty())
                assertTrue(messages.isEmpty())
                assertFalse(received.isCompleted)
                proxy.restore.countDown()
                assertEquals(event, received.await())
                pool.connected.first { url in it }
                assertEquals(2, attempts.get(), "Recovery must use a distinct pool attempt")
                assertEquals(listOf(2), opened.toList())
                assertEquals(listOf(2), messages.toList(), "Failed attempt must deliver no late data")
                assertEquals(List(2) { "CONNECT $onion:443 HTTP/1.1" }, proxy.requests.toList())
                assertEquals(2, proxy.accepted.size)
                assertEquals(2, proxy.accepted.map { it.remoteSocketAddress }.distinct().size)
                assertEquals(0, dnsCalls.get())
                val upgrade = requireNotNull(relay.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("GET / HTTP/1.1", upgrade.requestLine)
                assertEquals(onion, upgrade.getHeader("Host"))
                assertEquals(1, relay.requestCount)
                assertEquals(1, requestFrames.size)
                val filter = Json.parseToJsonElement(requestFrames.single()).jsonArray[2]
                assertEquals(Json.parseToJsonElement("{\"kinds\":[1]}"), filter)
                assertTrue(relayErrors.isEmpty(), relayErrors.toString())
                assertEquals(null, proxy.failure.get())
            }
        } finally {
            collector.cancelAndJoin()
            pool.stop()
            withTimeout(3_000) { scopeJob.cancelAndJoin() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            proxy.close()
            relay.shutdown()
            assertTrue(client.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(proxy.accepted.all { it.isClosed }, "Owned proxy sockets leaked")
            assertTrue(pool.connected.value.isEmpty())
            assertEquals(listOf(2), opened.toList())
            assertTrue(messages.none { it == 1 }, "Refused attempt delivered late data during cleanup")
        }
    }

    private class RejectThenRestoreProxy(private val relayPort: Int) {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val restore = CountDownLatch(1)
        val firstClosed = CountDownLatch(1)
        val requests = ConcurrentLinkedQueue<String>()
        val accepted = ConcurrentLinkedQueue<Socket>()
        val failure = AtomicReference<Throwable?>()
        private val outbound = ConcurrentLinkedQueue<Socket>()
        private val pumps = ConcurrentLinkedQueue<Thread>()
        @Volatile private var stopping = false
        private val worker = thread(name = "proxy-recovery-accept", isDaemon = true) {
            try {
                server.soTimeout = 5_000
                accept().use { first ->
                    requests += head(first)
                    first.getOutputStream().apply {
                        write("HTTP/1.1 504 Gateway Timeout\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush()
                    }
                    check(first.getInputStream().read() == -1) { "Rejected connection remained active" }
                }
                firstClosed.countDown()
                val second = accept()
                requests += head(second)
                check(restore.await(5, TimeUnit.SECONDS)) { "Proxy was not restored" }
                val backend = Socket("127.0.0.1", relayPort).also { outbound += it }
                second.soTimeout = 0
                second.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush()
                }
                pump(second, backend)
                pump(backend, second)
            } catch (error: Throwable) { if (!stopping) failure.set(error) }
        }

        private fun accept() = server.accept().also { it.soTimeout = 5_000; accepted += it }
        private fun head(socket: Socket): String {
            val input = socket.getInputStream()
            val bytes = StringBuilder()
            while (!bytes.endsWith("\r\n\r\n")) {
                val b = input.read()
                check(b >= 0 && bytes.length < 8192) { "Missing/oversized CONNECT head" }
                bytes.append(b.toChar())
            }
            return bytes.substringBefore("\r\n")
        }
        private fun pump(from: Socket, to: Socket) {
            pumps += thread(name = "proxy-recovery-pump", isDaemon = true) {
                try { from.getInputStream().copyTo(to.getOutputStream()) }
                catch (error: IOException) { if (!stopping) failure.compareAndSet(null, error) }
            }
        }
        fun close() {
            stopping = true
            restore.countDown()
            server.close()
            (accepted + outbound).forEach { runCatching { it.close() } }
            worker.join(2_000)
            pumps.forEach { it.join(2_000) }
            assertFalse(worker.isAlive)
            assertTrue(pumps.none { it.isAlive }, "Owned proxy workers leaked")
        }
    }
}
