package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.issueKindredProof
import dev.forgesworn.kithmoot.protocol.verifyKindredProof
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class KindredProofVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val input = vector.child("input")
        val expected = vector.child("output").child("proof")
        val tier = KindredTier.fromWire(input.text("tier"))
        assertNotNull("tier for $name", tier)

        val proof = issueKindredProof(
            issuerSecretKey = input.bytes("hostSkHex"),
            participant = input.text("participant"),
            tier = tier!!,
            roomId = input.text("roomId"),
            expiresAt = input.number("expiresAt"),
            nonce = input.text("nonce"),
            auxRand = input.bytes("auxRandHex"),
        )

        assertEquals("tier for $name", expected.text("tier"), proof.tier.wire)
        assertEquals("participant for $name", expected.text("participant"), proof.participant)
        assertEquals("issuer for $name", expected.text("issuer"), proof.issuer)
        assertEquals("room for $name", expected.text("room"), proof.room)
        assertEquals("nonce for $name", expected.text("nonce"), proof.nonce)
        assertEquals("expiry for $name", expected.number("expiresAt"), proof.expiresAt)
        assertEquals("signature for $name", expected.text("sig"), proof.sig)
        assertTrue("$name should verify", verifyKindredProof(proof))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("kindredProof")
    }
}
