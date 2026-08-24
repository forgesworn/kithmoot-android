package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RosterEventVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val input = vector.child("input")
        val output = vector.child("output")
        val expected = vector.childOrNull("expected")

        val event = if (input.containsKey("entry")) {
            val built = encodeRosterEvent(
                entry = RosterEntry.fromJson(input.child("entry")),
                roomId = input.text("roomId"),
                roomKey = input.bytes("roomKeyHex"),
                deviceSecretKey = input.bytes("deviceSkHex"),
                nonce = input.bytes("nonceHex"),
                auxRand = input.bytes("auxRandHex"),
            )
            assertEquals("roster event for $name", NostrEvent.fromJson(output.child("event")), built)
            built
        } else {
            NostrEvent.fromJson(input.child("event"))
        }

        val decodeWith = input.childOrNull("decode") ?: expected?.child("decode")
        val roomId = decodeWith?.textOrNull("roomId") ?: input.text("roomId")
        val roomKey = if (decodeWith != null && decodeWith.containsKey("roomKeyHex")) {
            decodeWith.bytes("roomKeyHex")
        } else {
            input.bytes("roomKeyHex")
        }
        val now = decodeWith?.number("now") ?: input.child("entry").number("updatedAt")

        val decoded = decodeRosterEvent(event, roomId, roomKey, now)
        val expectedEntry = expected?.childOrNull("result")
        if (expectedEntry == null) {
            // Every negative roster vector expects null - never an exception,
            // because this decoder runs inside a subscription callback.
            assertNull("$name should decode to null", decoded)
        } else {
            assertEquals("decoded entry for $name", RosterEntry.fromJson(expectedEntry), decoded)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("rosterEvent")
    }
}
