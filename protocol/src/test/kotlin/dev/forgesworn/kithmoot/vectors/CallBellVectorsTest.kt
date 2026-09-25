package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CallBellState
import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.EncodeCallBellOptions
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.callBellContentKey
import dev.forgesworn.kithmoot.protocol.callBellTag
import dev.forgesworn.kithmoot.protocol.decodeCallBellEvent
import dev.forgesworn.kithmoot.protocol.encodeCallBellEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every shared `callBell` vector: the tag/content-key derivation, both
 * positive encode+decode round trips, and every refusal the web decoder
 * enforces. See "Call bell" in `docs/protocol.md`.
 */
class CallBellVectorsTest {
    private fun vector(name: String): JsonObject = Vectors.group("callBell").single { it.text("name") == name }

    @Test fun `the day tag and content key match the web derivation`() {
        val value = vector("tag-for-day")
        val input = value.child("input")
        val output = value.child("output")
        val key = input.bytes("keyHex")
        assertEquals("tag", output.text("tag"), callBellTag(key, input.number("at")))
        assertEquals("next day tag", output.text("nextDayTag"), callBellTag(key, input.number("nextDayAt")))
        assertEquals("content key", output.text("contentKeyHex"), callBellContentKey(key).toHex())
    }

    @Test fun `a call starting encodes and decodes exactly like the web client`() {
        assertBellRoundTrips("valid-start")
    }

    @Test fun `a call ending after an hour encodes and decodes exactly like the web client`() {
        assertBellRoundTrips("valid-end")
    }

    private fun assertBellRoundTrips(name: String) {
        val value = vector(name)
        val input = value.child("input")
        val expectedEvent = input.child("event")
        val plaintext = kotlinx.serialization.json.Json.parseToJsonElement(input.text("plaintext")).let { it as JsonObject }
        val call = plaintext.child("call")

        val event = encodeCallBellEvent(
            EncodeCallBellOptions(
                roomId = input.text("roomId"),
                key = input.bytes("keyHex"),
                deviceSecretKey = input.bytes("deviceSkHex"),
                state = requireNotNull(CallBellState.from(plaintext.text("state"))),
                call = CallMembership(call.text("id"), call.number("since")),
                createdAt = expectedEvent.number("created_at"),
                throwawaySecretKey = input.bytes("throwawaySkHex"),
                nonce = input.bytes("nonceHex"),
                deviceAuxRand = input.bytes("deviceAuxRandHex"),
                throwawayAuxRand = input.bytes("auxRandHex"),
            ),
        )

        assertEquals("$name kind", expectedEvent.number("kind").toInt(), event.kind)
        assertEquals("$name created_at", expectedEvent.number("created_at"), event.createdAt)
        val expectedTags = expectedEvent.list("tags").map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals("$name tags", expectedTags, event.tags)
        assertEquals("$name content", expectedEvent.text("content"), event.content)
        assertEquals("$name pubkey", expectedEvent.text("pubkey"), event.pubkey)
        assertEquals("$name id", expectedEvent.text("id"), event.id)
        assertEquals("$name sig", expectedEvent.text("sig"), event.sig)

        val decode = input.child("decode")
        val decoded = decodeCallBellEvent(
            NostrEvent.fromJson(expectedEvent),
            decode.text("roomId"),
            decode.bytes("keyHex"),
            decode.number("now"),
        )
        val expected = value.child("output").child("result")
        requireNotNull(decoded) { "$name: expected a decoded bell" }
        assertEquals("$name state", expected.text("state"), decoded.state.wire)
        val expectedCall = expected.child("call")
        assertEquals("$name call id", expectedCall.text("id"), decoded.call.id)
        assertEquals("$name call since", expectedCall.number("since"), decoded.call.since)
        assertEquals("$name device", expected.text("device"), decoded.device)
        assertEquals("$name createdAt", expected.number("createdAt"), decoded.createdAt)
    }

    @Test fun `every refusal in the vectors is refused here too`() {
        for (name in listOf("wrong-room-key", "bad-signature", "expired", "future", "malformed-call-id", "wrong-version")) {
            val value = vector(name)
            val input = value.child("input")
            val decode = input.child("decode")
            val decoded = decodeCallBellEvent(
                NostrEvent.fromJson(input.child("event")),
                decode.text("roomId"),
                decode.bytes("keyHex"),
                decode.number("now"),
            )
            assertNull(name, decoded)
        }
    }

    @Test fun `hex inputs round trip through the shared codec`() {
        // Sanity check on the vector fixtures themselves, so a broken hex
        // helper fails loudly here rather than inside a signature mismatch.
        val bytes = "deadbeef".hexToBytes()
        assertEquals("deadbeef", bytes.toHex())
    }
}
