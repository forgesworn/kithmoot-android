package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.deriveRoom
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RoomDerivationVectorsTest(private val name: String, private val vector: JsonObject) {

    @Test
    fun reproducesVector() {
        val room = deriveRoom(vector.child("input").bytes("secretHex"))
        val output = vector.child("output")
        assertEquals("roomId for $name", output.text("roomId"), room.roomId)
        assertEquals("roomKey for $name", output.text("roomKeyHex"), room.roomKey.toHex())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = Vectors.parameters("roomDerivation")
    }
}
