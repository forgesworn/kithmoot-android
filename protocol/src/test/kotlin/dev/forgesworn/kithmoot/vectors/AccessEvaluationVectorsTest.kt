package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.evaluateAccess
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AccessEvaluationVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val input = vector.child("input")
        val expected = vector.child("output").child("result")

        val policy = RoomPolicy.fromJson(input.child("policy"))
        assertNotNull("policy for $name", policy)
        val proof = input.childOrNull("proof")?.let { KindredProof.fromJson(it) }

        val decision = evaluateAccess(
            policy = policy!!,
            participant = input.text("participant"),
            proof = proof,
            now = input.number("now"),
            roomId = input.text("roomId"),
        )

        assertEquals("admission for $name", expected.flag("admitted"), decision.admitted)
        assertEquals("reason for $name", expected.text("reason"), decision.reason)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("accessEvaluation")
    }
}
