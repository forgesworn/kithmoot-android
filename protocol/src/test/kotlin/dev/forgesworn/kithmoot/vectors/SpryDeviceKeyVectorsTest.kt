package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.SpryKey
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The per-room Spry device key and the roster's `spryKey` claim, shared with the web client. */
@RunWith(Parameterized::class)
class SpryDeviceKeyVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val input = vector.child("input")
        val output = vector.child("output")
        if (vector.text("kind") == "positive") {
            val deviceSk = input.bytes("deviceSkHex")
            assertEquals("secret for $name", output.text("sprySecretHex"), SpryKey.secret(deviceSk).toHex())
            assertEquals("public key for $name", output.text("spryKey"), SpryKey.publicKeyHex(deviceSk))
            // A claim in capitals is read back lower-cased.
            assertEquals("roster claim for $name", output.text("rosterSpryKey"), SpryKey.sanitise(output.text("spryKey").uppercase()))
        } else {
            assertEquals("roster claim for $name", output.textOrNull("rosterSpryKey"), SpryKey.sanitise(input["spryKey"]))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("spryDeviceKey")
    }
}
