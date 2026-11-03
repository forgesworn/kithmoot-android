package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.*

class NodeRoomCreatorTest {
    private val now = 1_900_000_000L
    private val owner = LocalSigner(ByteArray(32) { 2 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val pair = NodePair("https://node.example", service.pubkey, owner.pubkey)
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val identities = NodeJoinIdentityStore(NodeMemoryStorage())
    private val grants = mutableListOf<JsonObject>(); private var enrolments = 0; private var fail = false
    private val api = NodeClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); val body = Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
        var status = 200
        val result = when (request.url.encodedPath) {
            "/node/v1/enrol" -> { enrolments++; assertNotNull(store.custody(owner.pubkey, NodeEvidence.string(body, "room"))); buildJsonObject { put("enrolled", true) } }
            "/groups/v1/events" -> { grants += body; val room = NostrEvent.fromJson(body).tags.single { it[0] == "d" }[1]
                assertTrue(NodeKeeperState.read(requireNotNull(store.custody(owner.pubkey, room)).keeper, requireNotNull(store.custody(owner.pubkey, room)).node).devices.any { it.grant.event.id == NodeEvidence.string(body, "id") })
                if (fail) status = 503; buildJsonObject { put("accepted", true) } }
            else -> error("Unexpected request")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").header("Content-Type", "application/json")
            .body(result.toString().toResponseBody("application/json".toMediaType())).build()
    }.build(), { now })
    private fun creator() = NodeRoomCreator(store, identities, api, { true }, { now })
    @Test fun `authority and device are durable before enrolment and owner local key is not copied into the saved node room`(): Unit = runBlocking {
        val custody = creator().prepare(pair, owner)
        assertEquals(0, enrolments); assertTrue(grants.isEmpty()); assertEquals("creating", store.custody(owner.pubkey, custody.node.room)!!.phase)
        val made = creator().complete(custody.node.room, owner)
        assertEquals("admitted", made.custody.phase); assertEquals(1, enrolments); assertEquals(1, grants.size)
        val saved = made.saved("https://app.example/j/", "Room", now)
        assertTrue(saved.viaAccount); assertFalse(saved.json.getValue("identity").jsonObject.containsKey("participantKey"))
        assertEquals(custody.device, saved.devicePubkey); assertEquals(custody.node.event.id, saved.nodeRoom!!.event.id)
        assertEquals(custody.node.event.id, NodeRooms.parseLink(saved.joinUrl, now)!!.event.id)
    }
    @Test fun `lost grant ACK resumes same descriptor and signed grant without minting another room`(): Unit = runBlocking {
        val custom = pair.copy(turnPorts = NodeTurnPorts(34790, 53499))
        val custody = creator().prepare(custom, owner); fail = true
        assertEquals(custom.turnPorts, custody.node.turnPorts)
        assertFailsWith<NodeRequestException> { creator().complete(custody.node.room, owner) }
        assertEquals("creating", store.custody(owner.pubkey, custody.node.room)!!.phase)
        val original = grants.single(); fail = false
        val made = creator().complete(custody.node.room, owner)
        assertEquals(custom.turnPorts, made.custody.node.turnPorts)
        assertEquals(custody.node.event, made.custody.node.event); assertEquals(custody.device, made.identity.devicePubkey)
        assertEquals(listOf(original, original), grants)
        creator().complete(custody.node.room, owner); assertEquals(2, enrolments)
    }
}
