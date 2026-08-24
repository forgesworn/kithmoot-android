package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DeviceCredentialVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val input = vector.child("input")
        val output = vector.child("output")
        val expected = vector.childOrNull("expected")

        // Vectors that record a secret key expect byte-exact re-signing, using
        // the aux-rand they pin. Vectors that record only an event expect us to
        // verify what is already there.
        val event = if (input.containsKey("participantSkHex")) {
            val built = createDeviceCredential(
                participantSecretKey = input.bytes("participantSkHex"),
                devicePubkey = input.text("devicePubkey"),
                roomId = input.text("roomId"),
                expiresAt = input.number("expiresAt"),
                createdAt = input.number("createdAt"),
                auxRand = input.bytes("auxRandHex"),
            )
            assertEquals(
                "signed credential for $name",
                NostrEvent.fromJson(output.child("event")),
                built,
            )
            built
        } else {
            NostrEvent.fromJson(input.child("event"))
        }

        val verify = input.childOrNull("verify") ?: expected!!.child("verify")
        val result = output.childOrNull("result") ?: expected!!.child("result")

        val check = verifyDeviceCredential(event, verify.text("roomId"), verify.number("now"))
        if (result.flag("ok")) {
            assertTrue("$name should verify, got $check", check is CredentialCheck.Valid)
            check as CredentialCheck.Valid
            assertEquals("participant for $name", result.text("participant"), check.participant)
            assertEquals("device for $name", result.text("device"), check.device)
        } else {
            assertTrue("$name should be refused, got $check", check is CredentialCheck.Invalid)
            assertEquals(
                "rejection reason for $name",
                result.text("reason"),
                (check as CredentialCheck.Invalid).reason,
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("deviceCredential")
    }
}
