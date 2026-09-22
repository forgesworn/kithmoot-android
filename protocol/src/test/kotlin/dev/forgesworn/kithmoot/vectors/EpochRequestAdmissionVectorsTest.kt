package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.decodeEpochRequest
import dev.forgesworn.kithmoot.protocol.deriveEpochRequestKey
import dev.forgesworn.kithmoot.protocol.epochRequestAdmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every shared epochRequestAdmission vector is executed: the proof's derivation
 * and message, a whole request decoded by the desk, and the three refusals.
 */
class EpochRequestAdmissionVectorsTest {
    private fun vector(name: String) = Vectors.group("epochRequestAdmission").single { it.text("name") == name }

    @Test fun `the admission proof derives to the web bytes`() {
        val value = vector("admission-proof")
        val input = value.child("input")
        val output = value.child("output")
        val roomKey = input.bytes("roomKeyHex")
        assertEquals(output.text("requestKeyHex"), deriveEpochRequestKey(roomKey).toHex())
        assertEquals(
            output.text("admission"),
            epochRequestAdmission(roomKey, input.text("roomId"), input.text("authority"), input.text("device"), input.number("createdAt")),
        )
        assertEquals(
            "kithmoot/v1/epoch-request:" + input.text("roomId") + ":" + input.text("authority") + ":" + input.text("device") + ":" + input.number("createdAt"),
            input.text("message"),
        )
    }

    @Test fun `a whole request decodes and each refusal refuses`() {
        for (name in listOf("request", "request-without-admission", "request-under-another-key", "request-proof-for-another-moment")) {
            val value = vector(name)
            val decode = value.child("expected").child("decode")
            val event = NostrEvent.fromJson(value.child("input").child("event"))
            val result = decodeEpochRequest(
                event, decode.text("roomId"), decode.bytes("authoritySkHex"), decode.bytes("roomKeyHex"), decode.number("now"),
            )
            if (value.text("kind") == "negative") {
                assertNull("$name must be refused", result)
            } else {
                val expected = value.child("expected").child("result")
                assertNotNull("$name must decode", result)
                assertEquals("$name device", expected.text("device"), result!!.device)
                assertEquals("$name participant", expected.text("participant"), result.participant)
                assertEquals("$name request", expected.text("request"), result.request)
            }
        }
    }
}
