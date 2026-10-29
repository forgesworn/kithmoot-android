package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import org.junit.Assert.*
import org.junit.Test

class SpryTest {
    @Test fun readsTheActualWebDescriptorAndPreservesTheWholeWirePayload() {
        val vector = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/spry-descriptor-web.json")).readText()).jsonObject
        val now = vector.getValue("now").jsonPrimitive.long
        val room = deriveRoom(vector.getValue("roomSecret").jsonPrimitive.content.hexToBytes())
        val epoch = EpochKeys(0, room.roomId, room.roomKey)
        val event = NostrEvent.fromJson(vector.getValue("event"))
        val expected = vector.getValue("expected").jsonObject
        assertEquals(expected, Json.parseToJsonElement(Nip44.decrypt(event.content, epoch.key)))
        val decoded = requireNotNull(decodeSpryDescriptor(event, epoch, room.roomId, now, null))
        assertEquals(expected.getValue("spry"), decoded.offer.toJson())
        val encoded = encodeSpryDescriptor(decoded.offer, epoch, decoded.participant,
            NostrEvent.fromJson(expected.getValue("credential")), ByteArray(32) { 3 }, now, previous = decoded)
        assertEquals(expected, Json.parseToJsonElement(Nip44.decrypt(encoded.content, epoch.key)))
        assertNull(decodeSpryDescriptor(event, epoch, "ff".repeat(32), now, null))
        assertNull(decodeSpryDescriptor(event, EpochKeys(0, room.roomId, ByteArray(32) { 12 }), room.roomId, now, null))
        assertNull(decodeSpryDescriptor(event, epoch, room.roomId, now + 3601, null))
    }

    @Test fun requiresPublicHttpsAndBoundsAccessLifetime() {
        for (url in listOf("http://relay.example", "https://127.0.0.1", "https://10.0.0.1", "https://[::1]", "https://node.local", "https://user:secret@relay.example", "https://relay.example/path", "https://localhost.", "https://node.local.", "https://relay.example.")) {
            assertTrue(runCatching { spryEndpoint(url) }.isFailure)
        }
        val now = 1_800_000_000L
        val value = buildJsonObject { put("v", 1); put("endpoint", "https://relay.example:3744"); put("token", "a".repeat(43)); put("expiresAt", now + 100); put("roomKey", "must not be retained") }
        val offer = requireNotNull(SpryOffer.parse(value, now))
        assertFalse(offer.toString().contains("a".repeat(43)))
        assertFalse(offer.toJson().toString().contains("roomKey"))
        assertNull(SpryOffer.parse(value, now + 100))
        assertNull(SpryOffer.parse(value, now - SPRY_GRANT_SECONDS - 1000))
    }
}
