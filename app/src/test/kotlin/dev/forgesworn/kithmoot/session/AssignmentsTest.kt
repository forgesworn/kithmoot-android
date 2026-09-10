package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class AssignmentsTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/assignment-vectors.json")!!.readText()).jsonObject
    @Test fun signedTypeScriptHistoriesProduceIdenticalAndroidState() {
        val vectors = fixture.getValue("vectors").jsonArray
        assertEquals(29,vectors.size)
        for (value in vectors) {
            val v=value.jsonObject
            val result=projectAssignments(v.getValue("events").jsonArray.map(NostrEvent::fromJson),v.getValue("room").jsonPrimitive.content)
            val actual=buildJsonObject {
                put("assignments",JsonArray(result.assignments.map { it.fields }))
                put("pending",JsonArray(result.pending.map(::JsonPrimitive)))
                put("rejected",JsonArray(result.rejected.map(::JsonPrimitive)))
            }
            assertEquals(v["expected"],actual,v["name"].toString())
        }
    }
    @Test fun signedButUnshareablePayloadsAreRefused() {
        for(value in fixture.getValue("invalid").jsonArray) {
            val v=value.jsonObject
            assertEquals(v.getValue("valid").jsonPrimitive.boolean,assignmentPayload(NostrEvent.fromJson(v.getValue("event")),v.getValue("room").jsonPrimitive.content)!=null,v["name"].toString())
        }
    }
    @Test fun advertisedActionsMatchReferenceAllowlists() {
        for(value in fixture.getValue("actionVectors").jsonArray) {
            val v=value.jsonObject
            assertEquals(v["expected"],validateAssignmentActions(v["input"])?:JsonNull,v["name"].toString())
        }
    }
    @Test fun encryptedReferenceEnvelopesBindParticipantDeviceRoomAndChannel() {
        for(value in fixture.getValue("envelopeVectors").jsonArray) {
            val v=value.jsonObject
            val channel=(v["channel"] as? JsonPrimitive)?.takeIf{it.isString}?.content
            val decoded=decodeChatEvent(NostrEvent.fromJson(v.getValue("event")),v.getValue("room").jsonPrimitive.content,v.getValue("keyHex").jsonPrimitive.content.hexToBytes(),200,channel=channel)
            assertEquals(v["expectedAssignment"],decoded?.assignment?.toJson()?:JsonNull,v["name"].toString())
        }
        for(value in fixture.getValue("channels").jsonArray) {
            val v=value.jsonObject
            val derived=deriveChatChannel(v.getValue("room").jsonPrimitive.content,v.getValue("keyHex").jsonPrimitive.content.hexToBytes(),(v["channel"] as? JsonPrimitive)?.takeIf{it.isString}?.content)
            assertEquals(v["id"]?.jsonPrimitive?.content,derived.id)
            assertEquals(v["derivedKeyHex"]?.jsonPrimitive?.content,derived.key.toHex())
        }
    }
    @Test fun nativeSigningRoundTripsWithoutChangingTheSelectedTask() = runTest {
        val signer=LocalSigner(ByteArray(32){1})
        val room="a".repeat(64);val request="android_create_0001"
        val payload=AssignmentPayload(assignmentId(signer.pubkey,request),request,null,"d".repeat(64),buildJsonObject {
            put("op","create");put("objective","Check build 41");put("criteria","Return evidence");put("owner",signer.pubkey)
        })
        val signed=signAssignment(signer,room,payload,100)
        assertEquals(payload,assignmentPayload(signed,room))
        assertEquals("offered",projectAssignments(listOf(signed),room).assignments.single().status)
        val changed=object:ParticipantSigner by signer {
            override suspend fun sign(kind:Int,createdAt:Long,tags:List<List<String>>,content:String):NostrEvent =
                signer.sign(kind,createdAt,tags,content.replace("build 41","build 42"))
        }
        assertFailsWith<IllegalArgumentException>{signAssignment(changed,room,payload,100)}
    }
    @Test fun unknownAndIncorrectlyTypedOperationFieldsStayPrivate() {
        val valid=buildJsonObject{put("op","answer");put("text","Build 41")}
        assertTrue(validAssignmentOperation(valid))
        assertFalse(validAssignmentOperation(JsonObject(valid+mapOf("privateNotes" to JsonPrimitive("Private")))))
        assertFalse(validAssignmentOperation(JsonObject(valid+mapOf("text" to JsonPrimitive(41)))))
        assertFalse(validAssignmentOperation(JsonObject(valid+mapOf("text" to JsonPrimitive("\uFEFF\u00A0")))))
    }
}
