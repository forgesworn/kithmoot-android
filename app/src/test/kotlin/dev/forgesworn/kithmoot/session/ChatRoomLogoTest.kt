package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ChatRoomLogoTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/room-logo-chat-v1.json"))
        .bufferedReader().use { it.readText() }).jsonObject
    private val room = Fixtures.room()
    private val member = Fixtures.primary(room, 1, 2)
    private val image = requireNotNull(LogoImages.read(fixture.getValue("vectors").jsonArray.first().jsonObject
        .getValue("expected").jsonObject.getValue("roomLogo")))

    @Test fun `signed encrypted TypeScript logo messages retain every modelled field`() {
        val roomId = fixture.getValue("roomId").jsonPrimitive.content
        val key = fixture.getValue("roomKey").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val now = fixture.getValue("now").jsonPrimitive.long
        assertEquals(20, fixture.getValue("vectors").jsonArray.size)
        for (value in fixture.getValue("vectors").jsonArray) {
            val entry = value.jsonObject; val name = entry.getValue("name").jsonPrimitive.content
            val channel = entry.getValue("channel").jsonPrimitive.content
            val decoded = decodeChatEvent(NostrEvent.fromJson(entry.getValue("event")), roomId, key, now, channel = channel)
            assertEquals(entry.getValue("accepted").jsonPrimitive.boolean, decoded != null, name)
            if (decoded != null) {
                val actual = buildJsonObject {
                    put("id", decoded.id); put("participant", decoded.participant); put("device", decoded.device)
                    put("text", decoded.body); put("sentAt", decoded.sentAt)
                    put("roomLogo", requireNotNull(decoded.roomLogo).image?.toJson() ?: JsonNull)
                }
                assertEquals(entry.getValue("expected"), actual, name)
                assertNotNull(roomLogoFromMessage(decoded.body, actual.getValue("roomLogo"), channel, decoded.participant, decoded.sentAt))
            }
        }
    }

    private fun event(logo: LogoImage?, channel: String = "control") = encodeChatEvent(
        body = encodeRoomLogoOp(roomLogoOp(logo, 100_000L)), participant = member.participant,
        credential = member.credential, roomId = room.roomId, roomKey = room.roomKey,
        deviceSecretKey = member.deviceSecretKey, sentAt = 100L, channel = channel,
        roomLogo = RoomLogoPayload(logo),
    )

    @Test fun `native image and explicit removal round trip without public image tags`() {
        for (logo in listOf(image, null)) {
            val encoded = event(logo)
            val decoded = assertNotNull(decodeChatEvent(encoded, room.roomId, room.roomKey, 200, channel = "control"))
            assertEquals(logo?.toJson(), assertNotNull(decoded.roomLogo).image?.toJson())
            assertFalse(encoded.content.contains(image.data))
            assertFalse(encoded.content.contains(image.sha256))
            assertEquals(listOf(listOf("d", deriveChatChannel(room.roomId, room.roomKey, "control").id)), encoded.tags)
            assertNull(decodeChatEvent(encoded, room.roomId, room.roomKey, 200))
        }
    }

    @Test fun `native sending refuses ordinary channels and mixed statements before publication`() {
        assertFailsWith<IllegalArgumentException> { event(image, "main") }
        assertFailsWith<IllegalArgumentException> {
            encodeChatEvent(encodeRoomLogoOp(roomLogoOp(image, 100_000L)), member.participant, member.credential,
                room.roomId, room.roomKey, member.deviceSecretKey, 100, channel = "control",
                roomLogo = RoomLogoPayload(image), mentions = emptyList())
        }
    }

    @Test fun `gated logo reads still require room admission`() {
        val policy = RoomPolicy(KindredTier.KITH, listOf("a".repeat(64)))
        assertNull(decodeChatEvent(event(image), room.roomId, room.roomKey, 200, policy, "control"))
    }
}
