package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.PublicRelayAdvert
import dev.forgesworn.kithmoot.protocol.PublicRelayOffer
import dev.forgesworn.kithmoot.protocol.PublicRelayStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

class PublicRelayLoginTest {
    private val now = 1_793_000_000L
    private val host = "relay.example.org"
    private val key = "a".repeat(64)
    private val username = "${now + 3600}:pub-0123456789abcdef01234567"
    private val credential = "c2VjcmV0LXNlY3JldC1zZWNyZXQtMTI="
    private val udp = "turn:$host:3478?transport=udp"
    private val tls = "turns:$host:5349?transport=tcp"

    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val server = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private val loopback = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }

    @After fun stop() { runCatching { server.shutdown() } }

    /** The advert's URL has no port, so a test socket factory sends port 443 to the mock server. */
    private class PortRewriter(private val target: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                super.connect(InetSocketAddress((endpoint as InetSocketAddress).address, target), timeout)
            }
        }
        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    }

    private fun client(dns: Dns = loopback, tweak: OkHttpClient.Builder.() -> Unit = {}) = PublicRelayLoginClient(dns, {
        sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        socketFactory(PortRewriter(server.port))
        tweak()
    }, { now })

    private fun advert(
        name: String = host,
        urls: List<String> = listOf(udp, tls),
        status: PublicRelayStatus = PublicRelayStatus.Open,
    ) = PublicRelayAdvert(
        publicKey = key, eventId = "e".repeat(64), createdAt = now, expiresAt = now + 7200, region = "GB", status = status,
        offer = if (status == PublicRelayStatus.Open) PublicRelayOffer("https://$name/v1/public/credentials", urls) else null,
        host = if (status == PublicRelayStatus.Open) name else null,
    )

    private fun login(vararg changes: Pair<String, JsonElement?>): String {
        val fields = linkedMapOf<String, JsonElement>(
            "urls" to JsonArray(listOf(udp, tls).map(::JsonPrimitive)),
            "username" to JsonPrimitive(username),
            "credential" to JsonPrimitive(credential),
            "expiresAt" to JsonPrimitive(now + 3600),
        )
        for ((name, value) in changes) if (value == null) fields.remove(name) else fields[name] = value
        return JsonObject(fields).toString()
    }

    private fun reply(body: String, code: Int = 200, type: String? = "application/json") =
        MockResponse().setResponseCode(code).setBody(body).also { if (type != null) it.setHeader("Content-Type", type) }

    private fun fetch(client: PublicRelayLoginClient = client(), advert: PublicRelayAdvert = advert()): PublicRelayLoginResult =
        runBlocking { client.fetch(advert) }

    private fun issued(result: PublicRelayLoginResult): PublicRelayLogin {
        assertTrue(result.toString(), result is PublicRelayLoginResult.Issued)
        return (result as PublicRelayLoginResult.Issued).login
    }

    private fun failed(body: String, code: Int = 200, type: String? = "application/json") {
        server.enqueue(reply(body, code, type))
        assertEquals(PublicRelayLoginResult.Failed, fetch())
    }

    @Test fun postsExactlyTheLoginRequestAndReturnsTheCheckedLogin() {
        server.enqueue(reply(login()))
        val got = issued(fetch())
        assertEquals(key, got.key)
        assertEquals(host, got.host)
        assertEquals(listOf(udp, tls), got.urls)
        assertEquals(username, got.username)
        assertEquals(credential, got.credential)
        assertEquals(now + 3600, got.expiresAt)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/public/credentials", recorded.path)
        assertEquals("{\"v\":1}", recorded.body.readUtf8())
        // Exactly application/json: no charset appended.
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        for (name in listOf("Cookie", "Origin", "Referer", "Authorization")) assertNull(name, recorded.getHeader(name))
        assertTrue(recorded.getHeader("User-Agent").orEmpty().startsWith("okhttp/"))
        assertEquals(1, server.requestCount)
    }

    @Test fun anOperatorThatSetsACookieDoesNotGetItBack() {
        server.enqueue(reply(login()).addHeader("Set-Cookie", "session=1; Path=/"))
        server.enqueue(reply(login()))
        val client = client()
        issued(fetch(client))
        issued(fetch(client))
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun aRedirectIsNeverFollowed() {
        // The login waits behind the redirect: following it would reach it.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://$host/elsewhere"))
        server.enqueue(reply(login()))
        assertEquals(PublicRelayLoginResult.Failed, fetch())
        assertEquals(1, server.requestCount)
    }

    @Test fun aRedirectThatCarriesAValidLookingLoginIsStillARedirect() {
        server.enqueue(reply(login(), code = 307).setHeader("Location", "https://$host/x"))
        server.enqueue(reply(login()))
        assertEquals(PublicRelayLoginResult.Failed, fetch())
        assertEquals(1, server.requestCount)
    }

    @Test fun onlyA200WithJsonWillDo() {
        for (code in listOf(201, 202, 204, 404, 500, 503)) failed(login(), code = code)
        failed(login(), type = "text/plain")
        failed(login(), type = "application/jsonx")
        failed(login(), type = "text/html; application/json")
        failed(login(), type = "application/json-seq")
        failed(login(), type = null)
        failed("not json")
        failed("[]")
        failed("")
    }

    @Test fun jsonWithAParameterOrOddCaseIsFine() {
        server.enqueue(reply(login(), type = "application/json; charset=utf-8"))
        issued(fetch())
        server.enqueue(reply(login(), type = "Application/JSON"))
        issued(fetch())
    }

    @Test fun a429IsBusyAndNothingElseIs() {
        server.enqueue(reply("{}", code = 429))
        assertEquals(PublicRelayLoginResult.Busy, fetch())
        server.enqueue(reply("{}", code = 503))
        assertEquals(PublicRelayLoginResult.Failed, fetch())
    }

    @Test fun theBodyIsCappedAtFourKibibytes() {
        val json = login()
        // Exactly 4096 bytes is within the cap, 4097 is not.
        server.enqueue(reply(json + " ".repeat(4096 - json.length)))
        issued(fetch())
        server.enqueue(reply(json + " ".repeat(4097 - json.length)))
        assertEquals(PublicRelayLoginResult.Failed, fetch())
        // A declared length over the cap is refused before any of the body is read.
        val padded = login("pad" to JsonPrimitive("x".repeat(5000)))
        server.enqueue(reply(padded))
        assertEquals(PublicRelayLoginResult.Failed, fetch())
    }

    /** Bytes the client took off the connection for the response body, however it ended. */
    private class BodyBytes : EventListener() {
        val received = AtomicLong(-1)
        override fun responseBodyEnd(call: Call, byteCount: Long) { received.set(byteCount) }
    }

    @Test fun aBodyOfNoDeclaredLengthIsOnlyReadAsFarAsTheCap() {
        // 2 MB, no Content-Length, trickled at 8 KiB every 20 ms: reading all of it would take 5 seconds.
        val huge = login("pad" to JsonPrimitive("x".repeat(2_000_000)))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setChunkedBody(huge, 8192)
            .throttleBody(8192, 20, TimeUnit.MILLISECONDS))
        val listener = BodyBytes()
        val started = System.nanoTime()
        assertEquals(PublicRelayLoginResult.Failed, fetch(client { eventListener(listener) }))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
        // The cap is 4096 and the buffer reads in 8 KiB segments: nowhere near the 2 MB the server offered.
        val received = listener.received.get()
        assertTrue("$received", received in 4097..65_536)
    }

    @Test fun anExpiryThatIsNotACanonicalIntegerIsRefused() {
        val exact = "\"expiresAt\":${now + 3600}"
        assertTrue(login().contains(exact))
        // A leading zero would parse to the same number as the username's prefix: refused all the same.
        for (spelling in listOf("0${now + 3600}", "00${now + 3600}", "+${now + 3600}", "${now + 3600}.0", "${now + 3600}.", "${now + 3600}e0",
            "1.7930036E9", "17930036e2", "-0", "${now + 3600}0000000000000000", "0x${(now + 3600).toString(16)}")) {
            server.enqueue(reply(login().replace(exact, "\"expiresAt\":$spelling")))
            assertEquals(spelling, PublicRelayLoginResult.Failed, fetch())
        }
        server.enqueue(reply(login()))
        issued(fetch())
    }

    @Test fun aBodyThatIsNotValidUtf8IsRefused() {
        val bad = Buffer().write(login().toByteArray()).also { it.writeByte(0xC3); it.writeByte(0x28) }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(bad))
        assertEquals(PublicRelayLoginResult.Failed, fetch())
    }

    @Test fun everyReturnedUrlMustBeOneTheSignedAdvertNamed() {
        server.enqueue(reply(login("urls" to JsonArray(listOf(udp, "turn:evil.example.org:3478", "turn:$host:3478?transport=tcp", udp).map(::JsonPrimitive)))))
        assertEquals(listOf(udp), issued(fetch()).urls)
        failed(login("urls" to JsonArray(listOf(JsonPrimitive("turn:evil.example.org:3478")))))
        failed(login("urls" to JsonArray(emptyList())))
        failed(login("urls" to JsonPrimitive(udp)))
        failed(login("urls" to JsonArray(listOf(JsonPrimitive(5)))))
        failed(login("urls" to null))
    }

    @Test fun theUsernameMustMatchTheShapeAndCarryTheExpiry() {
        for (bad in listOf(
            "${now + 3599}:pub-0123456789abcdef01234567",
            "${now + 3600}:pub-0123456789ABCDEF01234567",
            "${now + 3600}:pub-0123",
            "${now + 3600}:pub-0123456789abcdef012345678",
            "${now + 3600}:pri-0123456789abcdef01234567",
            "x${now + 3600}:pub-0123456789abcdef01234567",
            ":pub-0123456789abcdef01234567",
            "${now + 3600}:pub-0123456789abcdef01234567\n",
            "",
        )) failed(login("username" to JsonPrimitive(bad)))
        failed(login("username" to null))
        failed(login("username" to JsonPrimitive(12)))
        server.enqueue(reply(login()))
        issued(fetch())
    }

    @Test fun theCredentialMustBeShortBase64() {
        for (bad in listOf("not base64!", "QUFB".repeat(17), "", "QUF", "QUFB=", "QQ=", "QQ==QUFB", " QUFB")) {
            failed(login("credential" to JsonPrimitive(bad)))
        }
        failed(login("credential" to null))
        failed(login("credential" to JsonPrimitive(5)))
        server.enqueue(reply(login("credential" to JsonPrimitive("QUFB".repeat(16)))))
        issued(fetch())
        server.enqueue(reply(login("credential" to JsonPrimitive("QQ=="))))
        issued(fetch())
    }

    @Test fun theExpiryMustFallInAnHourAndAMinuteFromNow() {
        fun withExpiry(expiry: JsonElement, label: Long) = login("expiresAt" to expiry, "username" to JsonPrimitive("$label:pub-0123456789abcdef01234567"))
        failed(withExpiry(JsonPrimitive(now), now))
        failed(withExpiry(JsonPrimitive(now - 1), now - 1))
        failed(withExpiry(JsonPrimitive(now + 3661), now + 3661))
        // The username's prefix has to be the expiry.
        failed(withExpiry(JsonPrimitive(now + 10), now + 11))
        failed(withExpiry(JsonPrimitive((now + 3600).toString()), now + 3600))
        failed(withExpiry(JsonPrimitive(now + 3600.5), now + 3600))
        failed(withExpiry(JsonPrimitive(-5), -5))
        failed(login("expiresAt" to null))
        server.enqueue(reply(withExpiry(JsonPrimitive(now + 3660), now + 3660)))
        assertEquals(now + 3660, issued(fetch()).expiresAt)
        server.enqueue(reply(withExpiry(JsonPrimitive(now + 1), now + 1)))
        assertEquals(now + 1, issued(fetch()).expiresAt)
    }

    @Test fun anAddressThatIsNotPublicIsNeverConnectedTo() {
        for (address in listOf("10.0.0.1", "127.0.0.1", "192.168.1.5", "169.254.169.254", "::1", "fd00::1")) {
            val dns = PublicOnlyDns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName(address)) })
            server.enqueue(reply(login()))
            assertEquals(address, PublicRelayLoginResult.Failed, fetch(client(dns)))
        }
        // Production's check refuses loopback even when the resolver answers with it.
        assertEquals(PublicRelayLoginResult.Failed, fetch(client(PublicOnlyDns(loopback))))
        // The server was reachable all along; it was just never asked.
        assertEquals(0, server.requestCount)
    }

    @Test fun anAddressThePolicyAllowsIsConnectedTo() {
        val dns = PublicOnlyDns(loopback, allowed = { true })
        server.enqueue(reply(login()))
        issued(fetch(client(dns)))
        assertEquals(1, server.requestCount)
    }

    @Test fun nothingIsSentToAnAdvertThatIsWithdrawnOrNamesAHostTheAppRefuses() {
        assertEquals(PublicRelayLoginResult.Failed, fetch(advert = advert(status = PublicRelayStatus.Withdrawn)))
        for (name in listOf("relay.example", "box.lan", "relay.local", "single", "relay.test")) {
            assertEquals(name, PublicRelayLoginResult.Failed, fetch(advert = advert(name = name, urls = listOf("turn:$name:3478"))))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun anOperatorThatNeverAnswersIsGivenUpOn() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        assertEquals(PublicRelayLoginResult.Failed, fetch(client { callTimeout(300, TimeUnit.MILLISECONDS) }))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
    }

    @Test fun aConnectionThatFailsIsFailedNotThrown() {
        server.shutdown()
        assertEquals(PublicRelayLoginResult.Failed, fetch())
    }

    @Test fun theProductionClientIsLockedDown() {
        val http = PublicRelayLoginClient().client
        assertEquals(Proxy.NO_PROXY, http.proxy)
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertFalse(http.retryOnConnectionFailure)
        assertSame(CookieJar.NO_COOKIES, http.cookieJar)
        assertNull(http.cache)
        assertEquals(4000, http.callTimeoutMillis)
        assertTrue(http.interceptors.isEmpty())
        assertTrue(http.networkInterceptors.isEmpty())
        assertTrue(http.dns is PublicOnlyDns)
        // Its own pool, per client.
        assertTrue(PublicRelayLoginClient().client.connectionPool !== http.connectionPool)
    }

    @Test fun neitherTheResultNorTheLoginShowsTheCredential() {
        server.enqueue(reply(login()))
        val result = fetch()
        for (text in listOf(result.toString(), issued(result).toString())) {
            assertFalse(text.contains(credential))
            assertFalse(text.contains(username))
            assertFalse(text.contains(host))
        }
    }

    @Test fun evictingDropsIdleConnectionsAndClosingStopsTheClient() {
        val client = client()
        server.enqueue(reply(login()))
        issued(fetch(client))
        client.evictAll()
        assertEquals(0, client.client.connectionPool.connectionCount())
        client.close()
        assertEquals(PublicRelayLoginResult.Failed, fetch(client))
    }
}
