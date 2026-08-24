package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.JoinUrlException
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeJoinUrl
import dev.forgesworn.kithmoot.protocol.encodeJoinUrl
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class JoinUrlVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        if (vector.text("kind") == "negative") refuses() else roundTrips()
    }

    private fun roundTrips() {
        val input = vector.child("input")
        val output = vector.child("output")
        val policy = input.childOrNull("policy")?.let { RoomPolicy.fromJson(it) }

        val url = encodeJoinUrl(
            base = input.text("base"),
            secret = input.bytes("secretHex"),
            relays = input.strings("relays"),
            policy = policy,
        )
        assertEquals("encoded URL for $name", output.text("url"), url)

        val decoded = decodeJoinUrl(output.text("url"))
        val expected = output.child("decoded")
        assertEquals("secret for $name", expected.text("secretHex"), decoded.secret.toHex())
        assertEquals("relays for $name", expected.strings("relays"), decoded.relays)
        if (expected.isNull("policy")) {
            assertNull("policy for $name", decoded.policy)
        } else {
            assertEquals("policy for $name", RoomPolicy.fromJson(expected.child("policy")), decoded.policy)
        }
    }

    private fun refuses() {
        val url = vector.child("input").text("url")
        try {
            decodeJoinUrl(url)
            fail("$name should have been refused, not decoded")
        } catch (e: JoinUrlException) {
            // The reference implementation's exact message; an independent
            // implementation only has to refuse, but matching it is free proof
            // that we refused for the same reason.
            assertEquals("rejection reason for $name", vector.child("output").text("error"), e.message)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("joinUrl")
    }
}
