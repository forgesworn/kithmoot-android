package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile
import dev.forgesworn.kithmoot.protocol.Events
import kotlinx.serialization.json.*
import kotlin.test.*

class ReactionsTest {
    private val room = Fixtures.room()
    private val owner = Fixtures.primary(room, 1, 2)
    private val target = ChatMessage("target", owner.participant, owner.devicePubkey, "Hello", 100)
    @Test fun `TypeScript reaction decodes and Android emits the matching contract`() {
        val vector = Json.parseToJsonElement(javaClass.getResource("/chat-reaction-typescript.json")!!.readText()).jsonObject
        val event = NostrEvent.fromJson(vector.getValue("event"))
        val expected = parseReaction(vector.getValue("reaction"))!!
        assertEquals(expected, decodeChatEvent(event, room.roomId, room.roomKey, 200)?.reaction)
        val android = encodeChatEvent(reactionText(expected), owner.participant, NostrEvent.fromJson(vector.getValue("credential")), room.roomId, room.roomKey, owner.deviceSecretKey, 100,
            id = "android-reaction-vector", nonce = ByteArray(32), auxRand = ByteArray(32), reaction = expected)
        val output = java.io.File("build/reports/android-reaction-vector.json")
        output.parentFile?.mkdirs(); output.writeText(android.toJson().toString())
        assertEquals(expected, decodeChatEvent(android, room.roomId, room.roomKey, 200)?.reaction)
    }
    @Test fun `rapid toggles converge regardless of delivery order and only change one sender`() {
        val add = target.copy(id = "z", reaction = toggleReaction(emptyList(), target, owner.participant, "❤️"))
        val remove = target.copy(id = "a", reaction = toggleReaction(listOf(add), target, owner.participant, "❤️"))
        val other = add.copy(id = "other", participant = "ab".repeat(32))
        val active = reactionUpdates(listOf(remove, other, add, add), target).filter { it.reaction!!.active }
        assertEquals(listOf(other), active)
        assertEquals(3, toggleReaction(listOf(remove, add), target, owner.participant, "❤️").revision)
        assertTrue(reactionUpdates(listOf(add), target.copy(participant = other.participant)).isEmpty())
    }
    @Test fun `reaction payloads reject invalid types and ranges`() {
        val valid = toggleReaction(emptyList(), target, owner.participant, "🤦").toJson()
        for ((field, value) in listOf("revision" to JsonPrimitive(0), "revision" to JsonPrimitive(2147483648), "revision" to JsonPrimitive("1"), "active" to JsonPrimitive("true"), "emoji" to JsonPrimitive("<img>"), "participant" to JsonPrimitive("wrong"), "messageId" to JsonPrimitive(""))) {
            assertNull(parseReaction(JsonObject(valid + (field to value))))
        }
        assertNotNull(parseReaction(valid))
    }
    @Test fun `reaction sender is authenticated and ciphertext stays in its room`() {
        val reaction = toggleReaction(emptyList(), target, owner.participant, "👍")
        val event = encodeChatEvent(reactionText(reaction), owner.participant, owner.credential, room.roomId, room.roomKey, owner.deviceSecretKey, 100, reaction = reaction)
        assertEquals(reaction, decodeChatEvent(event, room.roomId, room.roomKey, 200)?.reaction)
        assertFalse(event.content.contains("👍"))
        assertEquals(listOf(listOf("d", room.roomId)), event.tags)
        assertNull(decodeChatEvent(event, room.roomId, ByteArray(32) { 99 }, 200))
        val impostor = Fixtures.primary(room, 3, 4)
        val forged = encodeChatEvent(reactionText(reaction), owner.participant, owner.credential, room.roomId, room.roomKey, impostor.deviceSecretKey, 100, reaction = reaction)
        assertNull(decodeChatEvent(forged, room.roomId, room.roomKey, 200))
    }
    @Test fun `public profiles require a requested author valid signature and safe picture URL`() {
        fun event(content: String) = Events.sign(owner.deviceSecretKey, 0, 100, emptyList(), content)
        val profile = event("""{"display_name":" Rowan ","picture":"https://example.com/avatar.png"}""")
        assertEquals("Rowan", decodePublicProfile(profile, setOf(owner.devicePubkey), 100)?.name)
        assertNull(decodePublicProfile(profile, emptySet(), 100))
        assertNull(decodePublicProfile(profile.copy(sig = "00".repeat(64)), setOf(owner.devicePubkey), 100))
        val unsafe = event("""{"picture":"javascript:alert(1)"}""")
        assertNull(decodePublicProfile(unsafe, setOf(owner.devicePubkey), 100)?.picture)
    }
}
