package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class NodeSettingsControllerTest {
    private val now = 1_900_000_000L
    private val owner = LocalSigner(ByteArray(32) { 2 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private var actor: ParticipantSigner? = owner
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val requests = mutableListOf<String>()
    private val link = NodePairLink("https://node.example", "a".repeat(43), now + 300)
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); requests += request.url.encodedPath
        val body = when (request.url.encodedPath) {
            "/node/v1/info" -> buildJsonObject { put("v", 1); put("profile", "kithmoot-node/1"); put("origin", link.origin); put("serviceKey", service.pubkey)
                put("relay", "wss://node.example/events"); put("calls", "relay-only"); put("files", "off") }
            "/node/v1/claim" -> buildJsonObject { put("paired", true) }
            "/node/v1/status" -> buildJsonObject { put("v", 1); put("serviceKey", service.pubkey); put("version", "0.1.0"); put("ready", true); put("paired", true)
                put("turnReady", true); put("relayReady", true); put("rooms", 0); put("devices", 0); put("pending", 0) }
            else -> error("Unexpected request")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").header("Content-Type", "application/json")
            .body(body.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun controller() = NodeSettingsController(store, { actor }, { now }) { permission -> NodeClient(http, { now }, permission) }
    @Test fun `pair link arrival has no network effect and confirmation pins status to this account`(): Unit = runBlocking {
        val controller = controller(); controller.refreshLocal(); controller.offer(link)
        assertTrue(requests.isEmpty()); assertEquals(link.origin, controller.state.value.pairingOrigin)
        controller.confirmPairing(); assertEquals(listOf("/node/v1/info", "/node/v1/claim"), requests)
        assertEquals(owner.pubkey, store.pair(owner.pubkey)!!.owner); assertNull(controller.state.value.pairingOrigin)
        controller.refreshStatus(); assertTrue(controller.state.value.status!!.ready)
        val count = requests.size; controller.unpair(); assertEquals(count, requests.size); assertNull(store.pair(owner.pubkey))
    }
    @Test fun `account changed while signing sends no claim and saves no pairing`(): Unit = runBlocking {
        val switching = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                actor = LocalSigner(ByteArray(32) { 7 }); return owner.sign(kind, createdAt, tags, content)
            }
        }
        actor = switching; val controller = controller(); controller.offer(link); controller.confirmPairing()
        assertEquals(listOf("/node/v1/info"), requests); assertNull(store.pair(owner.pubkey))
        controller.refreshLocal(); assertNull(controller.state.value.pair); assertFalse(controller.state.value.busy)
    }
    @Test fun `stale closure buttons after account switch neither query nor forget the previous owner reference`(): Unit = runBlocking {
        val reference = NodeOperationRef(NodePair(link.origin, service.pubkey, owner.pubkey), "cd".repeat(16))
        store.rememberOperation(reference)
        val controller = controller(); controller.refreshLocal(); actor = LocalSigner(ByteArray(32) { 7 })
        controller.checkOperation(reference); controller.forgetOperation(reference)
        assertTrue(requests.isEmpty()); assertEquals(listOf(reference), store.operations(owner.pubkey))
    }
    @Test fun `duplicate confirmation taps cannot prompt the signer or claim twice`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var signatures = 0
        val waiting = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                signatures++; started.complete(Unit); release.await(); return owner.sign(kind, createdAt, tags, content)
            }
        }
        actor = waiting; val controller = controller(); controller.offer(link)
        val first = async { controller.confirmPairing() }; started.await()
        controller.confirmPairing(); assertEquals(1, signatures)
        release.complete(Unit); first.await(); assertEquals(1, requests.count { it == "/node/v1/claim" })
    }
}
