package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Chat envelope 2 where the published vectors cannot reach: they are all in epoch 0 of the main
 * chat, where the room id, the channel's root and the `d` tag are one value. Here a message is
 * read from a channel under a later epoch, where the device signs over the room's epoch-0 id
 * (`credentialRoomId`) and the channel's own id, which are different strings. Writers are not
 * switched on, so the events are built here, from the primitives.
 */
class ChatEnvelope2Test {
    private val room = Fixtures.room()
    private val owner = Fixtures.primary(room, 1, 2)
    private val epoch = deriveEpoch(RoomEpoch(1, ByteArray(32) { (90 + it).toByte() }))
    private val outerKey = ByteArray(32) { (30 + it).toByte() }
    private val now = 200L

    /** A place chat is read from: a root (the room, or an epoch) and the channel under it, whose id is the `d` tag. */
    private class Address(val rootId: String, val rootKey: ByteArray, val channel: String?) {
        private val derived = deriveChatChannel(rootId, rootKey, channel)
        val id: String get() = derived.id
        val key: ByteArray get() = derived.key
    }

    private val mainChat = Address(room.roomId, room.roomKey, null)
    private val epochChat = Address(epoch.id, epoch.key, null)
    private val epochControl = Address(epoch.id, epoch.key, "control")

    /** The JSON of an envelope-1 message, exactly as the encoder writes it. */
    private fun body(identity: RoomIdentity = owner, sentAt: Long = 100, id: String = "m1"): String = Nip44.decrypt(
        encodeChatEvent(
            body = "hello", participant = identity.participant, credential = identity.credential, roomId = room.roomId,
            roomKey = room.roomKey, deviceSecretKey = identity.deviceSecretKey, sentAt = sentAt, id = id,
        ).content,
        room.roomKey,
    )

    private fun digest(room: String, d: String, outer: String, at: Long, body: String) =
        Digests.sha256("kithmoot/v1/chat-envelope-2:$room:$d:$outer:$at:$body".toByteArray(Charsets.UTF_8))

    /**
     * An envelope-2 event on [address]: the device signature over [body] by [signer], the event by [outerSk]. The
     * digest names [digestRoom], [digestOuter] and [digestAt], and the event is made at [createdAt]; each is the
     * honest value unless a test changes it. [wrap] reshapes the envelope before it is encrypted.
     */
    private fun envelope2(
        body: String = body(),
        address: Address = mainChat,
        signer: ByteArray = owner.deviceSecretKey,
        outerSk: ByteArray = outerKey,
        createdAt: Long = 100,
        digestRoom: String = room.roomId,
        digestOuter: String = Schnorr.publicKeyHex(outerSk),
        digestAt: Long = createdAt,
        wrap: (JsonObject) -> JsonObject = { it },
    ): NostrEvent {
        val sig = Schnorr.sign(digest(digestRoom, address.id, digestOuter, digestAt, body), signer).toHex()
        val envelope = wrap(buildJsonObject { put("v", 2); put("body", body); put("sig", sig) })
        return Events.sign(outerSk, KIND_CHAT, createdAt, listOf(listOf("d", address.id)), Nip44.encrypt(envelope.toString(), address.key))
    }

    private fun decode(event: NostrEvent, address: Address = mainChat) =
        decodeChatEvent(event, address.rootId, address.rootKey, now, channel = address.channel, credentialRoomId = room.roomId)

    @Test
    fun `an envelope 2 message reads, and its event is signed by neither the device nor the participant`() {
        val message = assertNotNull(decode(envelope2()))
        assertEquals("hello", message.body)
        assertEquals(owner.participant, message.participant)
        assertEquals(owner.devicePubkey, message.device)
        assertEquals(100, message.sentAt)
        assertNotEquals(owner.devicePubkey, envelope2().pubkey)
    }

    @Test
    fun `the device signs over the room's epoch-0 id, in a later epoch and on a channel under it`() {
        for (address in listOf(epochChat, epochControl)) {
            assertNotEquals(room.roomId, address.id)
            assertNotNull(decode(envelope2(address = address), address), "signed over the room id, read on ${address.channel ?: "the epoch's main chat"}")
            // The channel's own id, or the epoch's, in the room's place is not what the credential names.
            assertNull(decode(envelope2(address = address, digestRoom = address.id), address), "signed over the root's id")
            assertNull(decode(envelope2(address = address, digestRoom = epoch.id), address), "signed over the epoch's id")
        }
        assertNotNull(decode(envelope2(address = mainChat), mainChat))
    }

    @Test
    fun `the digest binds the outer key, the time and the channel`() {
        assertNull(decode(envelope2(digestOuter = Schnorr.publicKeyHex(ByteArray(32) { (31 + it).toByte() }))), "another outer key: lifted")
        assertNull(decode(envelope2(body = body(sentAt = 101), createdAt = 101, digestAt = 100)), "restamped: signed for another time")
        assertNull(decode(envelope2(createdAt = 101)), "signed honestly for another time: sentAt is not created_at")
        assertNull(decode(envelope2(createdAt = 99)), "created_at earlier than sentAt")
        // The same signature under another channel's tag is not the channel it was made for.
        val real = envelope2(address = epochChat)
        val elsewhere = Events.sign(outerKey, KIND_CHAT, 100, listOf(listOf("d", epochControl.id)), Nip44.encrypt(
            Nip44.decrypt(real.content, epochChat.key), epochControl.key))
        assertNull(decode(elsewhere, epochControl))
    }

    @Test
    fun `the credential must name the device the body names, not the event's signer`() {
        val second = Fixtures.secondary(room, owner, 60)
        // Honest: the second device, with its own credential, signs its own message.
        val honest = envelope2(body = body(second), signer = second.deviceSecretKey)
        assertEquals(second.devicePubkey, assertNotNull(decode(honest)).device)
        // The second device's name and key on a body that carries the first device's credential.
        val forged = Json.parseToJsonElement(body(owner)).jsonObject.let { JsonObject(it + ("device" to JsonPrimitive(second.devicePubkey))) }.toString()
        assertNull(decode(envelope2(body = forged, signer = second.deviceSecretKey)), "credential names another device")
        // The first device's name on a body signed by the second.
        assertNull(decode(envelope2(body = body(owner), signer = second.deviceSecretKey)), "signed by a device the body does not name")
        // The participant must still be the one the credential belongs to.
        val impostor = Json.parseToJsonElement(body(owner)).jsonObject.let { JsonObject(it + ("participant" to JsonPrimitive("ff".repeat(32)))) }.toString()
        assertNull(decode(envelope2(body = impostor)), "credential belongs to another participant")
    }

    @Test
    fun `an envelope that is not exactly v 2, body and sig is refused`() {
        assertNotNull(decode(envelope2()))
        val cases: Map<String, (JsonObject) -> JsonObject> = mapOf(
            "v as a string" to { e -> JsonObject(e + ("v" to JsonPrimitive("2"))) },
            "v as null" to { e -> JsonObject(e + ("v" to JsonNull)) },
            "v as 1" to { e -> JsonObject(e + ("v" to JsonPrimitive(1))) },
            "v as 2.5" to { e -> JsonObject(e + ("v" to JsonPrimitive(2.5))) },
            "v as true" to { e -> JsonObject(e + ("v" to JsonPrimitive(true))) },
            "no sig" to { e -> JsonObject(e - "sig") },
            "no body" to { e -> JsonObject(e - "body") },
            "no v, so envelope 1 with the wrong shape" to { e -> JsonObject(e - "v") },
            "a fourth key" to { e -> JsonObject(e + ("extra" to JsonPrimitive(1))) },
            "body as an object" to { e -> JsonObject(e + ("body" to Json.parseToJsonElement(e.getValue("body").let { (it as JsonPrimitive).content }))) },
            "body as null" to { e -> JsonObject(e + ("body" to JsonNull)) },
            "sig as a number" to { e -> JsonObject(e + ("sig" to JsonPrimitive(1))) },
            "sig 127 characters" to { e -> JsonObject(e + ("sig" to JsonPrimitive((e.getValue("sig") as JsonPrimitive).content.dropLast(1)))) },
            "sig 129 characters" to { e -> JsonObject(e + ("sig" to JsonPrimitive((e.getValue("sig") as JsonPrimitive).content + "0"))) },
            "sig in upper case" to { e -> JsonObject(e + ("sig" to JsonPrimitive((e.getValue("sig") as JsonPrimitive).content.uppercase()))) },
            "sig not hex" to { e -> JsonObject(e + ("sig" to JsonPrimitive("g".repeat(128)))) },
        )
        for ((what, wrap) in cases) assertNull(decode(envelope2(wrap = wrap)), what)
    }

    @Test
    fun `envelope 1 is unchanged, the device signs the event, and a v key is not an envelope 1 field`() {
        // The ordinary event, signed by the device, as before.
        val ordinary = encodeChatEvent(
            body = "hello", participant = owner.participant, credential = owner.credential, roomId = room.roomId,
            roomKey = room.roomKey, deviceSecretKey = owner.deviceSecretKey, sentAt = 100,
        )
        assertEquals(owner.devicePubkey, ordinary.pubkey)
        assertNotNull(decode(ordinary))
        // An envelope-1 body under any other key is not read: nothing signs for the device.
        val borrowed = Events.sign(outerKey, KIND_CHAT, 100, listOf(listOf("d", room.roomId)), Nip44.encrypt(body(), room.roomKey))
        assertNull(decode(borrowed))
        // A `v` key makes the plaintext an envelope, so an envelope-1 message carrying one is refused rather than read.
        for (v in listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive("2"), JsonNull)) {
            val withV = JsonObject(Json.parseToJsonElement(body()).jsonObject + ("v" to v)).toString()
            val signed = Events.sign(owner.deviceSecretKey, KIND_CHAT, 100, listOf(listOf("d", room.roomId)), Nip44.encrypt(withV, room.roomKey))
            assertNull(decode(signed), "v = $v")
        }
        // The envelope is a JSON object; a bare array or string never reads.
        for (plaintext in listOf(JsonArray(emptyList()).toString(), "\"2\"", "2", "null")) {
            assertNull(decode(Events.sign(owner.deviceSecretKey, KIND_CHAT, 100, listOf(listOf("d", room.roomId)), Nip44.encrypt(plaintext, room.roomKey))), plaintext)
        }
    }

    @Test
    fun `an edit and a retraction match the original by participant and id, whichever envelope each is in`() {
        val original = encodeChatEvent(
            body = "first", participant = owner.participant, credential = owner.credential, roomId = room.roomId,
            roomKey = room.roomKey, deviceSecretKey = owner.deviceSecretKey, sentAt = 100, id = "orig",
        )
        val editBody = Nip44.decrypt(
            encodeChatEvent(
                body = "second", participant = owner.participant, credential = owner.credential, roomId = room.roomId,
                roomKey = room.roomKey, deviceSecretKey = owner.deviceSecretKey, sentAt = 110, id = "edit", replaces = "orig",
            ).content,
            room.roomKey,
        )
        val messages = listOf(decode(original), decode(envelope2(body = editBody, createdAt = 110))).map { assertNotNull(it) }
        val resolved = resolveConversation(messages).stream.single()
        assertEquals("second", resolved.shown.body)
        assertEquals(listOf("edit"), resolved.edits.map { it.id })
    }
}
