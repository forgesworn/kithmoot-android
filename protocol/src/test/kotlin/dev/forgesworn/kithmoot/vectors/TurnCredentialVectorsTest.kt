package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.mintTurnCredential
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TurnCredentialVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val input = vector.child("input")
        val expected = vector.child("output")

        val credential = mintTurnCredential(
            secret = input.text("secret"),
            ttlSeconds = input.decimal("ttlSeconds"),
            now = input.decimal("now"),
            name = input.textOrNull("name"),
        )

        assertEquals("username for $name", expected.text("username"), credential.username)
        assertEquals("credential for $name", expected.text("credential"), credential.credential)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("turnCredential")
    }
}
