package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.unwrapSignal
import dev.forgesworn.kithmoot.protocol.wrapSignal
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SignalWrapVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val input = vector.child("input")
        val output = vector.child("output")
        val expected = vector.childOrNull("expected")

        val wrap = if (input.containsKey("body")) {
            val built = wrapSignal(
                body = SignalBody.fromJson(input.child("body")),
                senderSecretKey = input.bytes("senderSkHex"),
                recipientPubkey = input.text("recipientPubkey"),
                ephemeralSecretKey = input.bytes("ephemeralSkHex"),
                createdAt = input.number("createdAt"),
                innerAuxRand = input.bytes("innerAuxRandHex"),
                outerAuxRand = input.bytes("outerAuxRandHex"),
                nonce = input.bytes("nip44NonceHex"),
            )
            assertEquals("inner event for $name", NostrEvent.fromJson(output.child("inner")), built.inner)
            assertEquals("gift wrap for $name", NostrEvent.fromJson(output.child("outer")), built.wrap)
            built.wrap
        } else {
            NostrEvent.fromJson(input.child("wrap"))
        }

        val unwrapWith = input.childOrNull("unwrap") ?: expected!!.child("unwrap")
        val unwrapped = unwrapSignal(
            wrap = wrap,
            recipientSecretKey = unwrapWith.bytes("recipientSkHex"),
            roomId = unwrapWith.text("roomId"),
        )

        val expectedResult = expected?.childOrNull("result")
        if (expectedResult == null) {
            assertNull("$name should unwrap to null", unwrapped)
        } else {
            assertEquals("sender for $name", expectedResult.text("from"), unwrapped?.from)
            assertEquals(
                "body for $name",
                SignalBody.fromJson(expectedResult.child("body")),
                unwrapped?.body,
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("signalWrap")
    }
}
