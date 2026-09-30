package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.RoomRelaysRecord
import dev.forgesworn.kithmoot.protocol.canonicalRoomRelays
import dev.forgesworn.kithmoot.protocol.decodeRoomRelaysOp
import dev.forgesworn.kithmoot.protocol.encodeRoomRelaysOp
import dev.forgesworn.kithmoot.protocol.verifyRoomRelays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shared `roomRelays` vector: the one signature the web client's
 * `signRoomRelays` produces, and the three ways a member refuses to believe
 * a relay list. See `room-relays.ts` and the `relays` op in `control.ts` in
 * the TypeScript reference implementation.
 */
class RoomRelaysVectorsTest {
    private fun vector(name: String) = Vectors.group("roomRelays").single { it.text("name") == name }

    @Test fun `the signed relay list verifies, canonicalises and round trips as a control op`() {
        val value = vector("room-relays-signature")
        val input = value.child("input")
        val output = value.child("output")
        val expected = value.child("expected")

        val relays = input.strings("relays")
        val canonical = canonicalRoomRelays(relays)
        assertEquals("canonical list", output.strings("canonical"), canonical)

        val verify = expected.child("verify")
        val sig = output.text("sig")
        assertTrue(
            "the authority's signature over the canonical list",
            verifyRoomRelays(
                roomId = verify.text("roomId"),
                version = verify.number("version"),
                relays = verify.strings("relays"),
                sig = sig,
                authority = verify.text("authority"),
            ),
        )
        assertTrue("expected.result", expected.flag("result"))

        // The chat body this travels as, and back again.
        val record = RoomRelaysRecord(canonical, input.number("version"), sig)
        assertEquals("encoded control op", output.text("text"), encodeRoomRelaysOp(record))
        val decoded = decodeRoomRelaysOp(output.text("text"))
        val expectedResult = output.child("result")
        requireNotNull(decoded) { "the encoded op must decode" }
        assertEquals("decoded relays", expectedResult.strings("relays"), decoded.relays)
        assertEquals("decoded version", expectedResult.number("version"), decoded.version)
        assertEquals("decoded sig", expectedResult.text("sig"), decoded.sig)
    }

    @Test fun `a higher version over the same signature is refused`() {
        assertRefused("room-relays-another-version")
    }

    @Test fun `a list sent out of canonical order is refused`() {
        assertRefused("room-relays-not-canonical")
    }

    @Test fun `a signature checked against a different authority is refused`() {
        assertRefused("room-relays-another-authority")
    }

    private fun assertRefused(name: String) {
        val value = vector(name)
        val input = value.child("input")
        val output = value.child("output")
        assertFalse(
            name,
            verifyRoomRelays(
                roomId = input.text("roomId"),
                version = input.number("version"),
                relays = input.strings("relays"),
                sig = input.text("sig"),
                authority = input.text("authority"),
            ),
        )
        assertFalse("$name expected.output.result", output.flag("result"))
    }
}
