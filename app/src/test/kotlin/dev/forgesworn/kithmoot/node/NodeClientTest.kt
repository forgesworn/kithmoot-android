package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Base64
import kotlin.test.*

class NodeClientTest {
    private val now = 1_900_000_000L
    private val owner = LocalSigner(ByteArray(32) { 2 })
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val device = LocalSigner(ByteArray(32) { 4 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), "ee".repeat(32), "https://node.example", service.pubkey, now).toJson(), now))
    private val pair = NodePair(node.origin, node.serviceKey, owner.pubkey)
    private val requests = mutableListOf<Request>()
    private var answer: JsonObject = buildJsonObject {
        put("v", 1); put("serviceKey", node.serviceKey); put("version", "0.1.0-rc.1"); put("ready", true); put("paired", true)
        put("turnReady", true); put("relayReady", true); put("rooms", 0); put("devices", 0); put("pending", 0)
    }
    private var status = 200
    private var contentType = "application/json"
    private var text: String? = null
    private fun client(allowed: () -> Boolean = { true }) = NodeClient(OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .header("Content-Type", contentType).body((text ?: answer.toString()).toResponseBody(contentType.toMediaType())).build()
    }.build(), { now }, allowed)
    private fun grant(status: String = "active"): JsonObject = Events.sign(root.secretKeyForStorage(), 27541, now,
        listOf(listOf("t", "grant"), listOf("grant", "ab".repeat(16)), listOf("d", node.room), listOf("device", device.pubkey), listOf("server", "node.example"),
            listOf("scope", "turn"), listOf("scope", "relay-read"), listOf("scope", "relay-write"), listOf("status", status), listOf("expiration", (now + 3600).toString())), "").toJson()
    private fun active(): NodeAccess.Active {
        val grant = (parseGroupEvent(grant(), now) as GroupEventVerdict.Ok).parsed as ParsedGrant
        return NodeAccess.Active(grant, "a".repeat(43), now + 3600)
    }
    @Test fun `requests bind the exact body origin method and fresh nonce to the current owner`(): Unit = runBlocking {
        val api = client(); val a = api.status(pair, owner); api.status(pair, owner)
        assertTrue(a.ready); assertNull(a.storeBytes); assertEquals(2, requests.size)
        val nonces = requests.map { request ->
            assertEquals("https://node.example/node/v1/status", request.url.toString()); assertEquals("POST", request.method)
            assertNull(request.header("Cookie")); assertNull(request.header("Referer"))
            assertEquals("application/json", request.body?.contentType()?.toString())
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8(); assertEquals("{}", body)
            val event = NostrEvent.fromJson(Json.parseToJsonElement(String(Base64.getDecoder().decode(request.header("Authorization")!!.removePrefix("Nostr ")))))
            assertTrue(Events.verify(event)); assertEquals(owner.pubkey, event.pubkey); assertEquals(27235, event.kind)
            assertEquals(listOf("u", request.url.toString()), event.tags[0]); assertEquals(listOf("method", "POST"), event.tags[1])
            assertEquals(listOf("payload", Digests.sha256(body.toByteArray()).toHex()), event.tags[2]); assertEquals("nonce", event.tags[3][0]); event.tags[3][1]
        }
        assertNotEquals(nonces[0], nonces[1])
    }
    @Test fun `withdrawal during signer roundtrip and mutated signatures send nothing`(): Unit = runBlocking {
        var allowed = true
        val withdrawn = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent { allowed = false; return owner.sign(kind, createdAt, tags, content) }
        }
        assertFailsWith<NodeRequestException> { client { allowed }.status(pair, withdrawn) }; assertTrue(requests.isEmpty())
        val mutated = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String) = owner.sign(kind, createdAt, tags + listOf(listOf("extra", "no")), content)
        }
        assertFailsWith<IllegalArgumentException> { client().status(pair, mutated) }; assertTrue(requests.isEmpty())
    }
    @Test fun `redirect non JSON oversized duplicate field and foreign service responses fail closed`(): Unit = runBlocking {
        val api = client()
        status = 302; assertFailsWith<NodeRequestException> { api.status(pair, owner) }
        status = 200; contentType = "text/html"; assertFailsWith<NodeRequestException> { api.status(pair, owner) }
        contentType = "application/json"; text = " ".repeat(65537); assertFailsWith<NodeRequestException> { api.status(pair, owner) }
        text = "{\"v\":1,\"v\":1}"; assertFailsWith<NodeRequestException> { api.status(pair, owner) }
        text = null; answer = JsonObject(answer + ("serviceKey" to JsonPrimitive(device.pubkey)))
        assertFailsWith<IllegalArgumentException> { api.status(pair, owner) }
    }
    @Test fun `own TLS443 is accepted only with the signed node profile and five minute login bound`(): Unit = runBlocking {
        answer = buildJsonObject { put("urls", JsonArray(node.turnUrls.map(::JsonPrimitive))); put("username", "${now + 300}:${"ab".repeat(12)}")
            put("credential", "a".repeat(27) + "="); put("expiresAt", now + 300); put("accessExpiresAt", now + 3600) }
        val api = client(); assertEquals(node.turnUrls, api.turn(node, active(), device).urls)
        assertEquals("turns:turn.node.example:443?transport=tcp", api.turn(node, active(), device).urls.last())
        answer = JsonObject(answer + ("urls" to JsonArray(listOf(JsonPrimitive("turns:foreign.example:443?transport=tcp")))))
        assertFailsWith<IllegalArgumentException> { api.turn(node, active(), device) }
        answer = JsonObject(answer + ("urls" to JsonArray(node.turnUrls.map(::JsonPrimitive))) + ("expiresAt" to JsonPrimitive(now + 301)))
        assertFailsWith<IllegalArgumentException> { api.turn(node, active(), device) }
    }
    @Test fun `authenticated issuance permits small clock skew but never an overlong issued login`(): Unit = runBlocking {
        fun issued(at: Long, end: Long) = buildJsonObject {
            put("urls", JsonArray(node.turnUrls.map(::JsonPrimitive))); put("username", "$end:${"ab".repeat(12)}")
            put("credential", "a".repeat(27) + "="); put("issuedAt", at); put("expiresAt", end); put("accessExpiresAt", now + 3600)
        }
        val api = client()
        answer = issued(now + 1, now + 301); assertEquals(now + 1, api.turn(node, active(), device).issuedAt)
        answer = issued(now + 31, now + 331); assertFailsWith<IllegalArgumentException> { api.turn(node, active(), device) }
        answer = issued(now, now + 301); assertFailsWith<IllegalArgumentException> { api.turn(node, active(), device) }
    }
    @Test fun `only signed explicit closure authorises cold closure while a reset stays a reset`(): Unit = runBlocking {
        val api = client(); val closure = NodeRooms.signClosure(node, root.secretKeyForStorage(), "cd".repeat(16), now)
        answer = buildJsonObject { put("state", "closed"); put("proof", requireNotNull(NodeRooms.readClosure(closure.toJson(), node, now)).toJson()) }
        assertIs<NodeAccess.Closed>(api.access(node, "ab".repeat(16), device))
        answer = buildJsonObject { put("state", "closed"); put("proof", grant()) }
        assertFailsWith<IllegalArgumentException> { api.access(node, "ab".repeat(16), device) }
        val reset = Events.sign(root.secretKeyForStorage(), 27541, now, listOf(listOf("t", "reset"), listOf("d", node.room), listOf("server", "node.example"), listOf("expiration", (now + 3600).toString())), "")
        answer = buildJsonObject { put("state", "reset"); put("event", reset.toJson()) }
        assertIs<NodeAccess.Reset>(api.access(node, "ab".repeat(16), device))
        answer = buildJsonObject { put("state", "removed"); put("event", grant("revoked")) }
        assertIs<NodeAccess.Removed>(api.access(node, "ab".repeat(16), device))
    }
    @Test fun `pair link is local bounded canonical public data until explicit pair`() {
        val body = buildJsonObject { put("v", 1); put("node", node.origin); put("code", "a".repeat(43)); put("expiresAt", now + 300) }
        val link = "https://app.example/#kithmoot-node=" + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray())
        val parsed = NodePairLink.parse(link, now); assertEquals(node.origin, parsed.origin); assertTrue(requests.isEmpty())
        assertFalse(parsed.toString().contains("a".repeat(43)))
        assertFailsWith<IllegalArgumentException> { NodePairLink.parse(link, now + 300) }
        val malformed = "https://[broken/#kithmoot-node=" + "s".repeat(43)
        val error = assertFailsWith<IllegalArgumentException> { NodePairLink.parse(malformed, now) }
        assertFalse(error.message!!.contains("s".repeat(43))); assertNull(error.cause)
    }
}
