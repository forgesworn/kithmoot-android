package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call a device says it is on.
 *
 * Two things matter here and nothing else does. A membership another
 * implementation wrote is only believed when it is well formed, because a
 * call id is a string somebody else chose and everything downstream compares
 * it. And an entry that is NOT on a call publishes no `call` key at all, so
 * the bytes are identical to what a client that has never heard of calls
 * would write - which is the whole reason this rides presence.
 */
class RosterCallMembershipTest {

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private val credential = NostrEvent(
        20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64),
    )

    private fun entry(call: CallMembership? = null) =
        RosterEntry("aa".repeat(32), "bb".repeat(32), credential, updatedAt = 1_800_000_000, call = call)

    @Test
    fun `a well-formed membership is read`() {
        val call = CallMembership.sanitise(parse("""{"id":"c0ffeec0ffeec0ffeec0ffeec0ffeec0","since":1799999940}"""))
        assertEquals(CallMembership("c0ffeec0ffeec0ffeec0ffeec0ffeec0", 1_799_999_940), call)
    }

    @Test
    fun `an id another client wrote in upper case is held to one spelling`() {
        // Every comparison downstream is a string comparison on this id - who
        // is on which call, whether to adopt it or mint a new one - so two
        // spellings of one call would be two calls.
        val call = CallMembership.sanitise(parse("""{"id":"C0FFEEC0FFEEC0FFEEC0FFEEC0FFEEC0","since":1}"""))
        assertEquals("c0ffeec0ffeec0ffeec0ffeec0ffeec0", call?.id)
    }

    @Test
    fun `a fractional time is floored to whole seconds`() {
        val call = CallMembership.sanitise(parse("""{"id":"c0ffeec0ffeec0ffeec0ffeec0ffeec0","since":1799999940.9}"""))
        assertEquals(1_799_999_940L, call?.since)
    }

    @Test
    fun `malformed memberships are dropped, not carried`() {
        val rubbish = listOf(
            """{"id":"short","since":1}""",
            """{"id":"c0ffeec0ffeec0ffeec0ffeec0ffeez0","since":1}""",
            """{"id":"c0ffeec0ffeec0ffeec0ffeec0ffeec0"}""",
            """{"id":"c0ffeec0ffeec0ffeec0ffeec0ffeec0","since":"1799999940"}""",
            """{"id":"c0ffeec0ffeec0ffeec0ffeec0ffeec0","since":-1}""",
            """{"since":1799999940}""",
            """{"id":123,"since":1}""",
        )
        for (text in rubbish) assertNull("$text should not be a membership", CallMembership.sanitise(parse(text)))
    }

    @Test
    fun `a malformed membership costs only the membership`() {
        // An entry with rubbish in `call` is still that device's presence.
        // Dropping the whole entry would drop a person out of the room over a
        // field that nothing structural depends on.
        val json = entry().toJson().toMutableMap()
        json["call"] = parse("""{"id":"nonsense","since":"soon"}""")
        val decoded = RosterEntry.fromJson(kotlinx.serialization.json.JsonObject(json))
        assertNull(decoded.call)
        assertEquals("bb".repeat(32), decoded.device)
    }

    @Test
    fun `an entry that is not on a call writes no call key at all`() {
        val json = entry().toJson()
        assertFalse(
            "the wire must stay byte-identical for a client that has never heard of calls",
            json.containsKey("call"),
        )
    }

    @Test
    fun `an entry on a call round-trips through JSON`() {
        val call = CallMembership("c0ffeec0ffeec0ffeec0ffeec0ffeec0", 1_799_999_940)
        val json = entry(call).toJson()
        assertTrue(json.containsKey("call"))
        assertEquals(call, RosterEntry.fromJson(json).call)
    }
}
