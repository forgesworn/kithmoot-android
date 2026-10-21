package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.decodeRoomNameOp
import dev.forgesworn.kithmoot.protocol.encodeRoomNameOp
import dev.forgesworn.kithmoot.protocol.roomNameFromMessage
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

    /** [skip]: vectors of the group that hold no events to decode. [whole]: also check who sent each line and when. */
    private fun conversationGroup(name: String, skip: Set<String> = emptySet(), whole: Boolean = false) {
        for (vector in group(name).filter { it.text("name") !in skip }) {
            val decode = vector.child("input").child("decode")
            val (roomId, roomKey, now) = decodeArgs(decode)
            val events = vector.child("input").getValue("events").jsonArray.map { NostrEvent.fromJson(it.jsonObject.child("event")) }
            // A vector in a later epoch is read as the app reads one: under that epoch's id and key, the credential still checked against the room.
            val epoch = decode["epochIdHex"]?.jsonPrimitive?.content?.let { it to decode.text("epochKeyHex").hexToBytes() }
            val results = events.map { event ->
                if (epoch == null) decodeChatEvent(event, roomId, roomKey, now)
                else decodeChatEvent(event, epoch.first, epoch.second, now, credentialRoomId = roomId)
            }
            val expected = vector.child("output").getValue("results").jsonArray
            assertEquals(expected.size, results.size, "${vector.text("name")} results")
            if (whole) {
                for (event in events) assertEquals(epoch?.first ?: roomId, event.tagValue("d"), "${vector.text("name")} d tag")
                // Whether the event's signer is the device the message names, as frozen: only envelope 1 has the device sign the event.
                val outerIsDevice = vector.child("output").getValue("outerIsDevice").jsonArray.map { it.jsonPrimitive.content.toBoolean() }
                val envelopes = vector.child("input").getValue("events").jsonArray.map { it.jsonObject.getValue("envelope").jsonPrimitive.content.toInt() }
                for (i in events.indices) {
                    assertEquals(envelopes[i] == 1, outerIsDevice[i], "${vector.text("name")} event $i outerIsDevice")
                    assertEquals(outerIsDevice[i] && results[i] != null, results[i]?.device == events[i].pubkey, "${vector.text("name")} event $i signer")
                }
            }
            for ((i, result) in results.withIndex()) {
                val want = expected[i]
                if (want is JsonNull) assertNull(result, "${vector.text("name")} event $i must be refused")
                else {
                    val decoded = assertNotNull(result, "${vector.text("name")} event $i must decode")
                    val w = want.jsonObject
                    if (whole) {
                        assertEquals(w.text("participant"), decoded.participant)
                        assertEquals(w.text("device"), decoded.device)
                        assertEquals(w.getValue("sentAt").jsonPrimitive.long, decoded.sentAt)
                        assertEquals(events[i].createdAt, decoded.sentAt, "${vector.text("name")} event $i: envelope 2 or 1, created_at is sentAt")
                    }
                    assertEquals(w.text("id"), decoded.id)
                    assertEquals(w.text("text"), decoded.body)
                    assertEquals(w["sentAtMs"]?.jsonPrimitive?.long, decoded.sentAtMs, "${vector.text("name")} event $i sentAtMs")
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
    @Test fun `messages in one second order as the reference does`() = conversationGroup("chatOrder")
    @Test fun `retractions resolve as the reference does`() = conversationGroup("chatRetract")

    @Test
    fun `envelope 2 events decode, are refused and resolve as the reference does`() {
        // All but the key derivation hold events to decode; that one is checked below. Two are read in epoch 1.
        assertEquals(13, group("chatEnvelope2").count { "events" in it.child("input") })
        assertEquals(listOf("message-in-epoch-1", "epoch-id-signed-as-room"), group("chatEnvelope2").filter { "events" in it.child("input") && "epochIdHex" in it.child("input").child("decode") }.map { it.text("name") })
        conversationGroup("chatEnvelope2", skip = setOf("outer-key-derivation"), whole = true)
    }

    @Test
    fun `the outer key and the digest of an envelope 2 message derive as the reference does, and its event rebuilds`() {
        val derivation = group("chatEnvelope2").single { it.text("name") == "outer-key-derivation" }
        val input = derivation.child("input")
        val output = derivation.child("output")
        val deviceSk = input.text("deviceSkHex").hexToBytes()
        val createdAt = input.getValue("createdAt").jsonPrimitive.long

        // The outer key, from the primitives rather than from the code under test.
        val outerSk = Digests.hkdfSha256(deviceSk, null, "kithmoot/v1/chat-outer:${input.text("d")}:${input.text("messageId")}".toByteArray(Charsets.UTF_8), 32)
        assertEquals(output.text("outerSkHex"), outerSk.toHex())
        val outer = Schnorr.publicKeyHex(outerSk)
        assertEquals(output.text("outerPubkey"), outer)

        // The digest, built by hand and by the reader's own function, with the hex in either case.
        val text = "kithmoot/v1/chat-envelope-2:${input.text("roomId")}:${input.text("d")}:$outer:$createdAt:${input.text("body")}"
        assertEquals(output.text("digestHex"), Digests.sha256(text.toByteArray(Charsets.UTF_8)).toHex())
        assertEquals(output.text("digestHex"), chatEnvelope2Digest(input.text("roomId"), input.text("d"), outer, createdAt, input.text("body")).toHex())
        assertEquals(output.text("digestHex"), chatEnvelope2Digest(input.text("roomId").uppercase(), input.text("d").uppercase(), outer.uppercase(), createdAt, input.text("body")).toHex())

        // The `message` vector is that message: rebuilt from its recorded draws it is the same event, byte for byte.
        val message = group("chatEnvelope2").single { it.text("name") == "message" }.child("input")
        val entry = message.getValue("events").jsonArray.single().jsonObject
        assertEquals(input.text("body"), entry.text("body"))
        assertEquals(output.text("outerSkHex"), entry.text("outerSkHex"))
        val sig = Schnorr.sign(Digests.sha256(text.toByteArray(Charsets.UTF_8)), deviceSk, entry.text("innerAuxRandHex").hexToBytes()).toHex()
        val wrapped = buildJsonObject { put("v", 2); put("body", input.text("body")); put("sig", sig) }.toString()
        val rebuilt = Events.sign(outerSk, KIND_CHAT, createdAt, listOf(listOf("d", input.text("d"))),
            Nip44.encrypt(wrapped, message.child("decode").text("roomKeyHex").hexToBytes(), entry.text("nonceHex").hexToBytes()), entry.text("auxRandHex").hexToBytes())
        assertEquals(NostrEvent.fromJson(entry.child("event")), rebuilt)
        assertEquals(outer, rebuilt.pubkey)
        assertTrue(outer != Schnorr.publicKeyHex(deviceSk), "the event is not signed by the device")
    }

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

    /** The reference's fixture keys (`vectors/lib/determinism.mjs`): HKDF over
     *  a label, reduced into the curve order as noble's `randomSecretKey` does. */
    private fun fixtureSecretKey(label: String): ByteArray {
        val ns = "kithmoot/v1/vectors"
        val ikm = Digests.sha256("$ns/sk-ikm/$label".toByteArray())
        val seed = java.math.BigInteger(1, Digests.hkdfSha256(ikm, null, "$ns/sk/$label".toByteArray(), 48))
        val n = java.math.BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        val key = seed.mod(n - java.math.BigInteger.ONE) + java.math.BigInteger.ONE
        return key.toByteArray().let { bytes -> ByteArray(32 - minOf(32, bytes.size)) + bytes.takeLast(32).toByteArray() }
    }

    @Test
    fun `room renames decode, read and rebuild as the reference does`() {
        val devices = listOf("device-a", "device-b").map(::fixtureSecretKey).associateBy { Schnorr.publicKeyHex(it) }
        val vectors = group("roomName").filter { "event" in it.child("input") }
        assertEquals(11, vectors.size)
        fun root(decode: JsonObject): Pair<String, ByteArray> = (decode["epoch"] as? JsonObject)
            ?.let { it.text("id") to it.text("keyHex").hexToBytes() } ?: (decode.text("roomId") to decode.text("roomKeyHex").hexToBytes())
        for (vector in vectors) {
            val name = vector.text("name")
            val input = vector.child("input")
            val output = vector.child("output")
            val event = NostrEvent.fromJson(input.child("event"))
            val decode = input.child("decode")
            val (rootId, rootKey) = root(decode)
            assertEquals(output.text("channelId"), deriveChatChannel(rootId, rootKey, "control").id, "$name channel")

            // Rebuilt under the key of the channel it was published to, which
            // for `rename-wrong-epoch` is not the one it is read with.
            val writer = if (name == "rename-wrong-epoch") group("roomName").single { it.text("name") == "rename-in-epoch-1" }.child("input").child("decode") else decode
            val (writerId, writerKey) = root(writer)
            val channel = deriveChatChannel(writerId, writerKey, "control")
            val sk = assertNotNull(devices[event.pubkey], "$name is signed by a fixture device")
            val rebuilt = Events.sign(sk, KIND_CHAT, input.child("message").getValue("sentAt").jsonPrimitive.long,
                listOf(listOf("d", channel.id)), Nip44.encrypt(input.child("message").toString(), channel.key, input.text("nonceHex").hexToBytes()),
                input.text("auxRandHex").hexToBytes())
            assertEquals(event, rebuilt, "$name rebuilds byte for byte")

            val message = decodeChatEvent(event, rootId, rootKey, decode.getValue("now").jsonPrimitive.long, channel = "control", credentialRoomId = decode.text("roomId"))
            if (output["message"] is JsonNull) {
                assertNull(message, "$name must not decode")
                assertTrue(output["record"] is JsonNull)
                continue
            }
            val m = assertNotNull(message, "$name must decode")
            val want = output.child("message")
            assertEquals(want.text("id"), m.id)
            assertEquals(want.text("participant"), m.participant)
            assertEquals(want.text("device"), m.device)
            assertEquals(want.text("text"), m.body)
            assertEquals(want.getValue("sentAt").jsonPrimitive.long, m.sentAt)
            assertEquals(want["sentAtMs"]?.jsonPrimitive?.long, m.sentAtMs, "$name sentAtMs")
            val op = decodeRoomNameOp(m.body)
            assertEquals(output["op"].toString(), op?.let { Json.parseToJsonElement(encodeRoomNameOp(it)).toString() } ?: "null", "$name op")
            val record = roomNameFromMessage(m.body, m.participant, m.sentAt)
            val recordJson = record?.let { r -> buildJsonObject {
                put("name", r.name); put("id", r.id); put("at", r.at); r.by?.let { put("by", it) }; put("sentAt", r.sentAt)
            } } ?: JsonNull
            assertEquals(output["record"], recordJson as JsonElement, "$name record")
            if (vector.text("kind") == "negative") assertNull(record, name) else assertNotNull(record, name)
        }
        for (vector in vectors) assertTrue("Book club" !in vector.child("input").child("event").toString(), "the name never crosses the wire in the clear")
    }
}
