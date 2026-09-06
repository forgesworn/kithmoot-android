package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.decodeJoinUrl
import dev.forgesworn.kithmoot.protocol.evaluateAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The message layer against the published vectors: replies and threads,
 * edits, retractions, mentions, DM invitations and read positions. The chat
 * codec lives in this module, so the vectors are read from the protocol
 * module's copy rather than duplicated; the copy is verbatim, never edited.
 */
class MessageLayerVectorsTest {

    private val root: JsonObject by lazy {
        val file = listOf("../protocol/src/test/resources/kithmoot-vectors.json", "protocol/src/test/resources/kithmoot-vectors.json")
            .map(::File).first { it.exists() }
        Json.parseToJsonElement(file.readText()).jsonObject
    }
    private fun group(name: String): List<JsonObject> = root.getValue("groups").jsonObject.getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.child(key: String) = getValue(key).jsonObject

    private fun decodeArgs(decode: JsonObject) = Triple(decode.text("roomId"), decode.text("roomKeyHex").hexToBytes(), decode.getValue("now").jsonPrimitive.long)

    private fun summarise(messages: List<ChatMessage>): JsonArray {
        fun one(r: ResolvedMessage): JsonObject = buildJsonObject {
            put("id", r.original.id)
            put("participant", r.original.participant)
            put("text", r.shown.body)
            put("edited", r.edited)
            put("edits", buildJsonArray { for (e in r.edits) add(JsonPrimitive(e.id)) })
            put("retracted", r.retracted)
            put("orphan", r.orphan)
            put("thread", r.thread?.toJson() ?: JsonNull)
            put("reply", r.reply?.toJson() ?: JsonNull)
            put("replies", buildJsonArray { for (reply in r.replies) add(one(reply)) })
        }
        return buildJsonArray { for (r in resolveConversation(messages).stream) add(one(r)) }
    }

    private fun conversationGroup(name: String) {
        for (vector in group(name)) {
            val (roomId, roomKey, now) = decodeArgs(vector.child("input").child("decode"))
            val events = vector.child("input").getValue("events").jsonArray.map { NostrEvent.fromJson(it.jsonObject.child("event")) }
            val results = events.map { decodeChatEvent(it, roomId, roomKey, now) }
            val expected = vector.child("output").getValue("results").jsonArray
            for ((i, result) in results.withIndex()) {
                val want = expected[i]
                if (want is JsonNull) assertNull(result, "${vector.text("name")} event $i must be refused")
                else {
                    val decoded = assertNotNull(result, "${vector.text("name")} event $i must decode")
                    val w = want.jsonObject
                    assertEquals(w.text("id"), decoded.id)
                    assertEquals(w.text("text"), decoded.body)
                    assertEquals(w["replaces"]?.jsonPrimitive?.content, decoded.replaces)
                    assertEquals(w["retracts"]?.jsonPrimitive?.content, decoded.retracts)
                    assertEquals(w["reply"]?.let { parseMessageRef(it) }, decoded.reply)
                    assertEquals(w["thread"]?.let { parseMessageRef(it) }, decoded.thread)
                    assertEquals((w["mentions"] as? JsonArray)?.map { it.jsonPrimitive.content }, decoded.mentions)
                }
            }
            if (vector.text("kind") == "negative") assertTrue(results.any { it == null }, "${vector.text("name")} refuses something")
            assertEquals(vector.child("output").getValue("conversation"), summarise(results.filterNotNull()) as JsonElement, "${vector.text("name")} conversation")
        }
    }

    @Test fun `threads resolve as the reference does`() = conversationGroup("chatThread")
    @Test fun `edits resolve as the reference does`() = conversationGroup("chatEdit")
    @Test fun `retractions resolve as the reference does`() = conversationGroup("chatRetract")

    @Test
    fun `mentions read as the reference does`() {
        for (vector in group("chatMention")) {
            val input = vector.child("input")
            val (roomId, roomKey, now) = decodeArgs(input.child("decode"))
            val result = decodeChatEvent(NostrEvent.fromJson(input.child("event")), roomId, roomKey, now)
            if (vector.text("kind") == "negative") { assertNull(result, vector.text("name")); continue }
            val decoded = assertNotNull(result, vector.text("name"))
            val roster = input.getValue("roster").jsonArray.map { it.jsonObject }.map { Named(it.text("participant"), it["name"]?.jsonPrimitive?.content) }
            val expected = vector.child("output").child("addressed")
            val rowan = roster[1].participant
            val tally = roster[2].participant
            assertEquals(expected.getValue("mentionsOf").jsonArray.map { it.jsonPrimitive.content }, mentionsOf(decoded, roster))
            assertEquals(expected.getValue("rowanAsPerson").jsonPrimitive.content.toBoolean(), mentionedBy(decoded, rowan, roster))
            assertEquals(expected.getValue("tallyAsPerson").jsonPrimitive.content.toBoolean(), mentionedBy(decoded, tally, roster))
            assertEquals(expected.getValue("tallyAsAgent").jsonPrimitive.content.toBoolean(), mentionedBy(decoded, tally, roster, agent = true))
        }
    }

    @Test
    fun `an invitation opens for the pair and nobody else`() {
        val vector = group("chatInvite").first { it.text("name") == "invite" }
        val input = vector.child("input")
        val (roomId, roomKey, now) = decodeArgs(input.child("decode"))
        val result = assertNotNull(decodeChatEvent(NostrEvent.fromJson(input.child("event")), roomId, roomKey, now))
        val invite = assertNotNull(result.invite)
        assertEquals(input.text("dmRoomId"), invite.room)
        val a = input.text("senderSkHex").hexToBytes()
        val b = input.text("recipientSkHex").hexToBytes()
        val c = input.text("strangerSkHex").hexToBytes()
        val sender = result.participant
        assertEquals(Schnorr.publicKeyHex(a), sender)
        val link = input.text("link")
        assertEquals(link, openInvite(invite, self = Schnorr.publicKeyHex(b), sender = sender, participantSecretKey = b))
        assertEquals(link, openInvite(invite, self = Schnorr.publicKeyHex(a), sender = sender, participantSecretKey = a))
        assertNull(openInvite(invite, self = Schnorr.publicKeyHex(c), sender = sender, participantSecretKey = c))
        // The sealed link reproduces byte for byte under the recorded nonce.
        val resealed = sealInvite(link, invite.to, invite.room, a, input.text("linkNonceHex").hexToBytes())
        assertEquals(invite.link, resealed.link)
        // And the link admits the two of them and nobody else.
        val policy = assertNotNull(decodeJoinUrl(link).policy)
        assertTrue(isDmPolicy(policy))
        assertEquals(Schnorr.publicKeyHex(a), dmPeer(policy, Schnorr.publicKeyHex(b)))
        assertEquals(false, evaluateAccess(policy, Schnorr.publicKeyHex(c), null, now, invite.room).admitted)
        for (negative in group("chatInvite").filter { it.text("kind") == "negative" }) {
            val (r, k, n) = decodeArgs(negative.child("input").child("decode"))
            for (e in negative.child("input").getValue("events").jsonArray) {
                assertNull(decodeChatEvent(NostrEvent.fromJson(e.jsonObject.child("event")), r, k, n), negative.text("name"))
            }
        }
    }

    @Test
    fun `read positions derive, decode, refuse and merge as the reference does`() {
        val derivation = group("readPosition").first { it.text("name") == "read-position-id-derivation" }
        assertEquals(derivation.child("output").text("idHex"), readPositionId(derivation.child("input").text("roomKeyHex").hexToBytes()))

        val positive = group("readPosition").first { it.text("name") == "read-position" }
        val input = positive.child("input")
        val event = NostrEvent.fromJson(input.child("event"))
        val decode = input.child("decode")
        val a = input.text("participantSkHex").hexToBytes()
        val record = assertNotNull(decodeReadPositions(event, decode.text("participant"), decode.text("roomId"), decode.text("roomKeyHex").hexToBytes(), a))
        assertEquals(input.text("plaintext"), readPositionPlaintext(record.room, record.read))
        val expected = positive.child("output").child("result")
        assertEquals(expected.text("room"), record.room)
        assertEquals(expected.child("read").keys, record.read.keys)
        // Rebuilt under the recorded nonce and aux-rand, the record is byte-identical.
        val rebuilt = encodeReadPositions(record.read, record.room, decode.text("roomKeyHex").hexToBytes(), a, event.createdAt,
            nonce = input.text("nonceHex").hexToBytes(), auxRand = input.text("auxRandHex").hexToBytes())
        assertEquals(event, rebuilt)

        for (negative in group("readPosition").filter { it.text("kind") == "negative" }) {
            val d = negative.child("input").child("decode")
            val e = NostrEvent.fromJson(negative.child("input").child("event"))
            val sk = negative.child("input").text("participantSkHex").hexToBytes()
            assertNull(decodeReadPositions(e, d.text("participant"), d.text("roomId"), d.text("roomKeyHex").hexToBytes(), sk), negative.text("name"))
        }

        val merge = group("readPosition").first { it.text("name") == "read-position-merge" }
        fun positions(o: JsonObject) = o.mapValues { (_, v) -> ReadPosition(v.jsonObject.getValue("at").jsonPrimitive.long, v.jsonObject["id"]?.jsonPrimitive?.content) }
        val merged = mergeReadPositions(positions(merge.child("input").child("local")), positions(merge.child("input").child("remote")))
        val want = merge.child("output")
        assertEquals(positions(want.child("merged")), merged.merged)
        assertEquals(want.getValue("localAhead").jsonPrimitive.content.toBoolean(), merged.localAhead)
        assertEquals(want.getValue("remoteAhead").jsonPrimitive.content.toBoolean(), merged.remoteAhead)
    }
}
