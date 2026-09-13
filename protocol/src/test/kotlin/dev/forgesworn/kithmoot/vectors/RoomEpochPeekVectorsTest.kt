package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.canonicalAdmins
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import dev.forgesworn.kithmoot.protocol.signAdmins
import dev.forgesworn.kithmoot.protocol.verifyAdmins
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every shared roomEpoch vector is executed, including encrypted rekeys. */
class RoomEpochPeekVectorsTest {
    private fun vector(name: String): JsonObject = Vectors.group("roomEpoch").single { it.text("name") == name }

    @Test fun `epoch zero and both successor derivations match the web bytes`() {
        for (name in listOf("epoch-zero-is-the-room", "epoch-one", "epoch-two")) {
            val value = vector(name)
            val input = value.child("input")
            val output = value.child("output")
            val keys = deriveEpoch(RoomEpoch(input.number("epoch").toInt(), input.bytes("secretHex")))
            assertEquals("$name epoch", output.number("epoch").toInt(), keys.epoch)
            assertEquals("$name id", output.text("id"), keys.id)
            assertEquals("$name key", output.text("keyHex"), keys.key.toHex())
        }
    }

    @Test fun `all five rekey outcomes match the web decoder`() {
        for (name in listOf("rekey", "rekey-read-by-the-removed-device", "rekey-closed", "rekey-not-the-authority", "rekey-skips-an-epoch")) {
            val value = vector(name)
            val input = value.child("input")
            val decode = value.childOrNull("expected")?.childOrNull("decode") ?: input.child("decode")
            val event = NostrEvent.fromJson(input.child("event"))
            val expectedPeek = value.child("output")["peek"]
            val actualPeek = peekRekeyEpoch(event, decode.text("roomId"), decode.text("authority"))
            if (expectedPeek != null) {
                if (expectedPeek == JsonNull) assertNull("$name peek", actualPeek)
                else assertEquals("$name peek", expectedPeek.jsonPrimitive.int, actualPeek)
            }

            val current = decode.child("current")
            val notice = decodeRekeyEvent(
                event,
                decode.text("roomId"),
                decode.text("authority"),
                EpochKeys(current.number("epoch").toInt(), current.text("id"), current.bytes("keyHex")),
                decode.bytes("deviceSkHex"),
            )
            val expected = value.child("output")["result"]
            if (expected == null || expected == JsonNull) {
                assertNull("$name decode", notice)
            } else {
                val result = expected as JsonObject
                requireNotNull(notice)
                assertEquals("$name epoch", result.number("epoch").toInt(), notice.epoch)
                assertEquals("$name removed", result.strings("removed"), notice.removed)
                assertEquals("$name closed", result.flag("closed"), notice.closed)
                assertEquals("$name by", result.textOrNull("by"), notice.by)
                assertEquals("$name at", result.number("at"), notice.at)
                result.textOrNull("secretHex")?.let { assertArrayEquals("$name secret", it.hexToBytes(), notice.secret) }
                    ?: assertNull("$name secret", notice.secret)
            }
        }
    }

    @Test fun `both admin signature vectors bind the canonical set to its epoch`() {
        val positive = vector("admins-signature")
        val input = positive.child("input")
        val output = positive.child("output")
        val admins = input.strings("admins")
        assertEquals(output.strings("canonical"), canonicalAdmins(admins))
        val signature = signAdmins(
            input.text("roomId"), input.number("epoch").toInt(), admins,
            input.bytes("authoritySkHex"), input.bytes("auxRandHex"),
        )
        assertEquals(output.text("sig"), signature)
        val verify = positive.child("expected").child("verify")
        assertTrue(verifyAdmins(verify.text("roomId"), verify.number("epoch").toInt(), verify.strings("admins"), signature, verify.text("authority")))

        val negative = vector("admins-signature-another-epoch").child("input")
        assertFalse(verifyAdmins(negative.text("roomId"), negative.number("epoch").toInt(), negative.strings("admins"), negative.text("sig"), negative.text("authority")))
    }
}
