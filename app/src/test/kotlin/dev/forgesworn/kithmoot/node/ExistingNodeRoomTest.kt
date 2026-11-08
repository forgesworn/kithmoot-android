package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.*

class ExistingNodeRoomTest {
    private val now=1_900_000_000L
    private val owner=LocalSigner(ByteArray(32){2})
    private val root=LocalSigner(ByteArray(32){3})
    private val service=LocalSigner(ByteArray(32){5})
    private val secret=ByteArray(32){6}
    private val room=deriveRoom(secret)
    private val own=PrimaryIdentity.create(room.roomId,now+86400,now,participantSecretKey=ByteArray(32){2},deviceSecretKey=ByteArray(32){4})
    private val android=PrimaryIdentity.create(room.roomId,now+86400,now,participantSecretKey=ByteArray(32){2},deviceSecretKey=ByteArray(32){9})
    private val web=PrimaryIdentity.create(room.roomId,now+86400,now,participantSecretKey=ByteArray(32){2},deviceSecretKey=ByteArray(32){10})
    private val guest=PrimaryIdentity.create(room.roomId,now+86400,now,participantSecretKey=ByteArray(32){7},deviceSecretKey=ByteArray(32){8})
    private val policy=RoomPolicy(KindredTier.OPEN,members=listOf(own.participant,guest.participant),quiet=true)
    private val link=encodeJoinUrl("https://app.example/j/",secret,listOf("wss://original.example"),policy)
    private val source=SavedRoom.create(secret,own,link,listOf("wss://original.example"),"Existing chat",now,null,root.pubkey)
    private val pair=NodePair("https://node.example",service.pubkey,owner.pubkey,NodeTurnPorts(34790,53499))
    private val store=NodeStore(NodeMemoryStorage(),NodeMemoryStorage(),NodeMemoryStorage())
    private val posts=mutableListOf<JsonObject>()
    private var fail=false
    private val client=NodeClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request=chain.request()
        val body=Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
        val held=requireNotNull(store.custody(owner.pubkey,source.id))
        assertNull(source.nodeRoom)
        assertEquals(own.devicePubkey,held.device)
        val result=when(request.url.encodedPath) {
            "/node/v1/enrol" -> { assertEquals(JsonPrimitive(true),body["existing"]);buildJsonObject {put("enrolled",true)} }
            "/groups/v1/events" -> { posts+=body
                assertTrue(NodeKeeperState.read(held.keeper,held.node).devices.any {it.grant.event.id==body.getValue("id").jsonPrimitive.content})
                buildJsonObject {put("accepted",true)} }
            else -> error("Unexpected request")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(fail&&request.url.encodedPath=="/groups/v1/events")503 else 200)
            .message("fixture").header("Content-Type","application/json").body(result.toString().toResponseBody("application/json".toMediaType())).build()
    }.build(),{now})
    private suspend fun prepare()=prepareExistingNodeRoom(source,own,root.secretKeyForStorage(),3,
        listOf(android.credential,web.credential,guest.credential),listOf(own.participant,guest.participant),emptyList(),pair,owner,store,client,{true},{now})

    @Test fun `all three concurrent account devices and a peer are provisioned without replacing the original room`():Unit=runBlocking {
        val prepared=prepare()
        assertEquals(source.id,prepared.custody.node.room);assertEquals(pair.turnPorts,prepared.custody.node.turnPorts)
        assertEquals(4,prepared.grants.size);assertEquals(4,posts.size)
        assertNull(source.nodeRoom);assertFalse(source.destruct)
        for(identity in listOf(own,android,web)) {
            val grant=prepared.grants.single {raw -> NostrEvent.fromJson(raw).tags.any {it==listOf("device",identity.devicePubkey)}}
            val original=SavedRoom.create(secret,identity,link,listOf("wss://original.example"),source.name,now,null,root.pubkey)
            val deviceStore=if(identity===own)store else NodeStore(NodeMemoryStorage(),NodeMemoryStorage(),NodeMemoryStorage())
            val moved=acceptExistingNodeDestination(original,identity,prepared.custody.node,grant,3,deviceStore,now)
            assertEquals(original.id,moved.id);assertEquals(identity.devicePubkey,moved.devicePubkey)
            assertEquals(original.json.getValue("identity"),moved.json.getValue("identity"))
            assertEquals(policy,moved.policy);assertFalse(moved.destruct)
            assertEquals(listOf(prepared.custody.node.relay),moved.relays)
            assertNotNull(deviceStore.custody(identity.participant,source.id))
        }
    }
    @Test fun `an unknown acknowledgement retries the same destination and signed grant`():Unit=runBlocking {
        fail=true
        assertFailsWith<NodeRequestException>{prepare()}
        val first=posts.single();val node=store.custody(owner.pubkey,source.id)!!.node
        assertNull(source.nodeRoom)
        fail=false
        val prepared=prepare()
        assertEquals(node.event.id,prepared.custody.node.event.id)
        assertEquals(first,posts[1]);assertEquals(4,prepared.grants.size)
    }
    @Test fun `a different device grant cannot move the original room`():Unit=runBlocking {
        val prepared=prepare()
        val other=prepared.grants.single {raw -> NostrEvent.fromJson(raw).tags.any {it==listOf("device",android.devicePubkey)}}
        val fresh=NodeStore(NodeMemoryStorage(),NodeMemoryStorage(),NodeMemoryStorage())
        assertFailsWith<IllegalArgumentException>{acceptExistingNodeDestination(source,own,prepared.custody.node,other,3,fresh,now)}
        assertNull(fresh.custody(owner.pubkey,source.id));assertNull(source.nodeRoom)
    }
    @Test fun `the original agent admission rule survives Android destination signing`():Unit=runBlocking {
        val body=Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(link.substringAfter('#')))).jsonObject
        val withRule=JsonObject(body+("a" to JsonObject(body.getValue("a").jsonObject+("agents" to JsonPrimitive("owned-by-members")))))
        val originalLink=link.substringBefore('#')+"#"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(withRule.toString().toByteArray())
        val original=SavedRoom.decode(JsonObject(source.json+("joinUrl" to JsonPrimitive(originalLink))))
        val prepared=prepareExistingNodeRoom(original,own,root.secretKeyForStorage(),3,
            listOf(android.credential,web.credential,guest.credential),listOf(own.participant,guest.participant),emptyList(),pair,owner,store,client,{true},{now})
        assertEquals(JsonPrimitive("owned-by-members"),prepared.custody.node.existing?.policy?.get("agents"))
        val moved=original.withExistingNodeDestination(prepared.custody.node,now)
        assertEquals(original.migrationPolicyEvidence(),moved.migrationPolicyEvidence())
    }

}
