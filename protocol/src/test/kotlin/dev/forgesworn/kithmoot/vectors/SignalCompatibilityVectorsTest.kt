package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.unwrapSignal
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SignalCompatibilityVectorsTest(private val name: String, private val vector: JsonObject) {
    @Test fun readsBothForms() {
        val input = vector.child("input")
        val expected = vector.child("expected")
        val result = unwrapSignal(NostrEvent.fromJson(input.child("wrap")), input.bytes("recipientSkHex"), input.text("roomId"), now = input.number("now"))
        val accepted = expected.childOrNull("result")
        if (accepted == null) assertNull(name, result)
        else {
            assertEquals(name, accepted.text("from"), result?.from)
            assertEquals(name, SignalBody.fromJson(accepted.child("body")), result?.body)
            assertEquals(name, expected.text("innerId"), result?.id)
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("signalCompatibility")
    }
}
