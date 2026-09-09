package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPolicyQuietTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun parse(s: String) = RoomPolicy.fromJson(Json.parseToJsonElement(s).jsonObject)

    @Test
    fun quietRidesWithAMembersListAndIsRefusedInAnyOtherShape() {
        val p = parse("""{"tier":"open","members":["$a","$b"],"quiet":true}""")
        assertTrue(p!!.quiet)
        assertEquals(listOf(a, b), p.members)
        assertEquals(true, p.toJson()["quiet"]?.toString()?.let { it == "true" })
        assertNull(parse("""{"tier":"open","quiet":true}"""))
        assertNull(parse("""{"tier":"open","members":["$a"],"quiet":"yes"}"""))
        assertNull(parse("""{"tier":"open","members":["$a"],"quiet":false}"""))
        assertNull(parse("""{"tier":"open","members":["$a"],"quiet":1}"""))
        val plain = parse("""{"tier":"open","members":["$a"]}""")
        assertTrue(plain != null && !plain.quiet)
        assertNull(plain!!.toJson()["quiet"])
    }
}
