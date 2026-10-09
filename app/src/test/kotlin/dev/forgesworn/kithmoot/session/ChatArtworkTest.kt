package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class ChatArtworkTest {
    private val room = Fixtures.room()
    private val owner = Fixtures.primary(room, 1, 2)
    private val coffee = catalogueArtwork(searchMediaCatalogue("coffee", false).single())

    @Test fun `reopened durable outbox retains the exact encrypted reference and cannot erase it through text-only editing`() = runBlocking<Unit> {
        val storage = object : RoomStorage {
            var bytes: ByteArray? = null
            override fun read() = bytes?.clone()
            override fun write(value: ByteArray) { bytes = value.clone() }
            override fun reset() { bytes = null }
        }
        val encoded = event(listOf(coffee))
        val outbox = PendingChatOutbox(storage, room.roomId, owner.participant, owner.devicePubkey)
        outbox.retain(room.roomId, encoded, editable = false, text = "A caption", messageId = "held-art")
        val reopened = PendingChatOutbox(storage, room.roomId, owner.participant, owner.devicePubkey)
        val held = assertNotNull(reopened.pending())
        assertEquals(encoded, held.event)
        assertEquals(listOf(coffee), assertNotNull(decodeChatEvent(held.event, room.roomId, room.roomKey, 200)).artwork)
        assertNull(reopened.take(encoded.id, editableOnly = true))
        assertNotNull(reopened.pending())
    }

    @Test fun `only generated titles of entirely resolved artwork are hidden`() {
        assertEquals("", artworkMessageText("GIF: Coffee", listOf(coffee)))
        assertEquals("My coffee break", artworkMessageText("My coffee break", listOf(coffee)))
        assertEquals("GIF: Coffee", artworkMessageText("GIF: Coffee", listOf(coffee.copy(sha256 = "0".repeat(64)))))
        assertEquals("Plain text", artworkMessageText("Plain text", emptyList()))
    }

    @Test fun `signed encrypted TypeScript artwork message decodes with its original reference`() {
        val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/artwork-references.json"))
            .bufferedReader().use { it.readText() }).jsonObject.getValue("encryptedMessage").jsonObject
        val expected = fixture.getValue("message").jsonObject
        val key = fixture.getValue("roomKey").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = assertNotNull(decodeChatEvent(NostrEvent.fromJson(fixture.getValue("event")),
            fixture.getValue("roomId").jsonPrimitive.content, key, fixture.getValue("now").jsonPrimitive.long))
        assertEquals(expected.getValue("id").jsonPrimitive.content, decoded.id)
        assertEquals(expected.getValue("participant").jsonPrimitive.content, decoded.participant)
        assertEquals(expected.getValue("device").jsonPrimitive.content, decoded.device)
        assertEquals(expected.getValue("text").jsonPrimitive.content, decoded.body)
        assertEquals(expected.getValue("sentAt").jsonPrimitive.long, decoded.sentAt)
        assertEquals(expected.getValue("artwork"), JsonArray(decoded.artwork.map { it.toJson() }))
        assertEquals("coffee", assertNotNull(resolveCatalogueArtwork(decoded.artwork.single())).slug)
    }

    @Test fun `shared TypeScript normalisation vectors match exactly`() {
        val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/artwork-references.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        fixture.getValue("normalisation").jsonArray.forEach { row ->
            val fields = row.jsonObject
            assertEquals(fields.getValue("expected"), parseArtwork(fields.getValue("input"))?.toJson() ?: JsonNull,
                fields.getValue("name").jsonPrimitive.content)
        }
    }

    @Test fun `an edit replaces artwork and an edit without artwork removes the original`() {
        val original = ChatMessage("original", owner.participant, owner.devicePubkey, "First", 100, artwork = listOf(coffee))
        val sticker = catalogueArtwork(searchMediaCatalogue("coffee", true).single())
        val edit = original.copy(id = "edit", body = "Second", sentAt = 101, replaces = original.id, artwork = listOf(sticker))
        assertEquals(listOf(sticker), resolveConversation(listOf(original, edit)).stream.single().shown.artwork)
        assertTrue(resolveConversation(listOf(original, edit.copy(artwork = emptyList()))).stream.single().shown.artwork.isEmpty())
    }

    private fun event(artwork: List<ChatArtwork> = emptyList(), reaction: ChatReaction? = null) = encodeChatEvent(
        "A caption", owner.participant, owner.credential, room.roomId, room.roomKey, owner.deviceSecretKey, 100,
        artwork = artwork, reaction = reaction)

    private fun received(entries: JsonElement, other: Pair<String, JsonElement>? = null): ChatMessage? {
        val original = event()
        val fields = Json.parseToJsonElement(Nip44.decrypt(original.content, room.roomKey)).jsonObject
        val payload = JsonObject(fields + ("artwork" to entries) + (other?.let { mapOf(it) } ?: emptyMap()))
        val wrapped = Events.sign(owner.deviceSecretKey, KIND_CHAT, 100, original.tags, Nip44.encrypt(payload.toString(), room.roomKey))
        return decodeChatEvent(wrapped, room.roomId, room.roomKey, 200)
    }

    @Test fun `references are encrypted without public artwork tags and preserve captions`() {
        val encoded = event(listOf(coffee))
        assertFalse(encoded.content.contains(coffee.sha256))
        assertTrue(encoded.tags.none { it.any { tag -> tag.contains("coffee") || tag.contains("artwork") } })
        val decoded = assertNotNull(decodeChatEvent(encoded, room.roomId, room.roomKey, 200))
        assertEquals(listOf(coffee), decoded.artwork)
        assertEquals("A caption", decoded.body)
        assertTrue(decoded.attachments.isEmpty())
        assertTrue(Nip44.decrypt(encoded.content, room.roomKey).length < 2_000)
    }

    @Test fun `malformed entries drop while unknown valid references stay readable`() {
        val unknown = coffee.copy(pack = "future-pack", id = "future", label = "Future art")
        val entries = JsonArray(listOf(JsonPrimitive("wrong"), coffee.toJson(), unknown.toJson(), coffee.copy(kind = "video").toJson()))
        val decoded = assertNotNull(received(entries))
        assertEquals(listOf(coffee, unknown), decoded.artwork)
        assertEquals("A caption", decoded.body)
        assertNull(resolveCatalogueArtwork(unknown))
        assertEquals("GIF: Future art", artworkFallback(listOf(unknown)))
        assertNull(received(JsonArray(List(5) { coffee.toJson() })))
    }

    @Test fun `identifiers types hashes and labels are checked without accepting URLs`() {
        for (bad in listOf(coffee.copy(pack = "HTTPS"), coffee.copy(id = "../coffee"), coffee.copy(kind = "image/gif"),
            coffee.copy(sha256 = "a".repeat(63)), coffee.copy(pack = "a".repeat(65)))) assertNull(normaliseArtwork(bad))
        assertNull(parseArtwork(JsonObject(coffee.toJson() + ("label" to JsonPrimitive(12)))))
        assertEquals(coffee, parseArtwork(JsonObject(coffee.toJson() + ("url" to JsonPrimitive("https://tracker.test")))))
        assertEquals(coffee, parseArtwork(coffee.copy(sha256 = coffee.sha256.uppercase()).toJson()))
        assertEquals("Coffee cup", normaliseArtwork(coffee.copy(label = "\tCoffee\u00a0\u202ecup\ue000\u0000\n"))!!.label)
        assertEquals(coffee.id, normaliseArtwork(coffee.copy(label = "\u202e\n"))!!.label)
        assertEquals("😀".repeat(80), normaliseArtwork(coffee.copy(label = "😀".repeat(81)))!!.label)
    }

    @Test fun `all known hashes resolve only the reviewed local PNGs and acted GIFs`() {
        val images = searchMediaCatalogue("", true) + searchMediaCatalogue("", false)
        assertEquals(31, images.size)
        images.forEach { image ->
            val reference = catalogueArtwork(image)
            assertEquals(image, resolveCatalogueArtwork(reference))
            val bytes = File("src/main/assets/${image.asset}").readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(hash, reference.sha256)
            assertNull(resolveCatalogueArtwork(reference.copy(sha256 = "0".repeat(64))))
            assertNull(resolveCatalogueArtwork(reference.copy(kind = if (reference.kind == "gif") "sticker" else "gif")))
        }
    }

    @Test fun `sender refuses invalid excess artwork and statement combinations`() {
        assertFailsWith<IllegalArgumentException> { event(List(5) { coffee }) }
        assertFailsWith<IllegalArgumentException> { event(listOf(coffee.copy(kind = "video"))) }
        val target = ChatMessage("target", owner.participant, owner.devicePubkey, "Hello", 99)
        val reaction = toggleReaction(emptyList(), target, owner.participant, "👍")
        assertFailsWith<IllegalArgumentException> { event(listOf(coffee), reaction) }
        assertNull(received(JsonArray(listOf(coffee.toJson())), "reaction" to reaction.toJson()))
        assertNull(received(JsonArray(listOf(coffee.toJson())), "retracts" to JsonPrimitive("target")))
        assertNull(received(JsonArray(listOf(coffee.toJson())), "assignment" to JsonNull))
    }
}
