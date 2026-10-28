package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

class SpryControlTest {
    @Test fun authenticatesTheSelectedRelayAndAcceptsOnlyThePinnedEncryptedReply(): Unit = runBlocking {
        exchange(200, buildJsonObject { put("ok", true) }) { client, endpoint ->
            assertEquals(buildJsonObject { put("ok", true) }, SpryControl.request(client, endpoint, "/v1/grants", buildJsonObject { put("scope", "generated") }, ByteArray(32) { 2 }))
        }
    }

    // As over HTTPS: a refusal ends the wait with its status, and none of the node's text.
    @Test fun aRefusalOnTheControlChannelCarriesItsStatus(): Unit = runBlocking {
        exchange(403, buildJsonObject { put("error", "Approval required.") }) { client, endpoint ->
            val refused = runCatching { SpryControl.request(client, endpoint, "/v1/grants", buildJsonObject { put("scope", "generated") }, ByteArray(32) { 2 }) }
                .exceptionOrNull()
            assertTrue(refused is SpryRequestException)
            assertEquals(403, (refused as SpryRequestException).status)
            assertFalse(refused.message!!.contains("Approval"))
        }
    }

    private suspend fun exchange(status: Int, answer: JsonObject, run: suspend (OkHttpClient, String) -> Unit) {
        val node = ByteArray(32) { 1 }
        val failure = AtomicReference<Throwable?>()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("relay.example").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            private var authenticated = false
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = Json.parseToJsonElement(text).jsonArray
                    fun send(value: JsonArray) { webSocket.send(value.toString()) }
                    when (frame[0].jsonPrimitive.content) {
                        "REQ" -> if (!authenticated) send(buildJsonArray { add("AUTH"); add("generated-challenge") })
                            else send(buildJsonArray { add("EOSE"); add("spry-reply") })
                        "AUTH" -> {
                            val event = NostrEvent.fromJson(frame[1]); assertTrue(Events.verify(event)); assertEquals(22242, event.kind)
                            assertEquals("generated-challenge", event.tagValue("challenge"))
                            authenticated = true; send(buildJsonArray { add("OK"); add(event.id); add(true); add("") })
                        }
                        "EVENT" -> {
                            val event = NostrEvent.fromJson(frame[1]); assertTrue(Events.verify(event))
                            val conversation = Nip44.conversationKey(node, event.pubkey.hexToBytes())
                            val body = Json.parseToJsonElement(Nip44.decrypt(event.content, conversation)).jsonObject
                            assertEquals("/v1/grants", body.getValue("path").jsonPrimitive.content)
                            val now = System.currentTimeMillis() / 1000
                            val tags = listOf(listOf("p", event.pubkey), listOf("e", event.id), listOf("expiration", (now + 30).toString()))
                            val content = Nip44.encrypt(buildJsonObject { put("v", 1); put("status", status); put("body", answer) }.toString(), conversation)
                            for (key in listOf(ByteArray(32) { 3 }, node)) {
                                val reply = Events.sign(key, 24182, now, tags, content)
                                send(buildJsonArray { add("EVENT"); add("spry-reply"); add(reply.toJson()) })
                            }
                        }
                    }
                } catch (error: Throwable) { failure.set(error); webSocket.cancel() }
            }
        }))
        server.start()
        try {
            val relay = "wss://relay.example:${server.port}/"
            val endpoint = "spry://${Schnorr.publicKeyHex(node)}?relay=${URLEncoder.encode(relay, "UTF-8") }"
            run(client, endpoint)
            failure.get()?.let { throw it }
        } finally {
            server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
