package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            // And again through the JSON, which is the comparison that
            // actually holds this client to the vector. The line above parses
            // the expected entry through the same model as the decoded one,
            // so a field this client does not model is dropped from BOTH
            // sides and the vector passes without the behaviour existing -
            // which is exactly what happened to `name` and `assist` for
            // months. Re-encoding and comparing the objects catches a field
            // that was published, recorded, and quietly ignored here.
            assertEquals(
                "re-encoded entry for $name",
                canonical(expectedEntry),
                canonical(decoded!!.toJson()),
            )
            // Nothing this client silently ignores except what it says it
            // ignores. A field that turns up in a published vector and is
            // not on that list fails here rather than passing quietly, which
            // is what `name` did for months.
            for (field in expectedEntry.keys) {
                assertTrue(
                    "$name carries \"$field\", which this client neither models nor declares",
                    field in MODELLED || field in NOT_MODELLED_YET,
                )
            }
        }
    }

    /**
     * A JSON object with its keys sorted, so two encoders that agree on the
     * fields but not their order compare equal - the vectors pin the bytes
     * of the EVENT, and the plaintext order is checked there. What this
     * catches is a field present in one and absent from the other.
     */
    private fun canonical(json: JsonObject): JsonObject =
        JsonObject(
            json.entries
                .filter { it.key !in NOT_MODELLED_YET }
                .sortedBy { it.key }
                .associate { it.key to it.value },
        )

    companion object {
        /** Every roster field this client reads and writes. */
        private val MODELLED = setOf(
            "participant", "device", "credential", "name", "tracks", "claims", "updatedAt", "proof", "agent", "reply", "left",
        )

        /**
         * Every roster field this client knowingly drops, and why.
         *
         * A short list on purpose. Anything here decodes and re-encodes as
         * nothing, so this client is forward-compatible with it and useless
         * for it; anything NOT here and not modelled fails the test above.
         * The point is that the list can only shrink by somebody doing the
         * work, never grow by somebody not noticing.
         *
         * `assist`: an offer to relay other people's media. Peer assist is
         * not implemented on Android at all - it consumes forwarders and
         * cannot act as one - so carrying the offer would advertise a
         * capability that does not exist here.
         */
        private val NOT_MODELLED_YET = setOf("assist")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("rosterEvent")
    }
}
