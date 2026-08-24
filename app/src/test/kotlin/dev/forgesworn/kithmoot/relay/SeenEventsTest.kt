package dev.forgesworn.kithmoot.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeenEventsTest {

    @Test
    fun `an id is admitted once and refused thereafter`() {
        val seen = SeenEvents()
        assertTrue(seen.admit("a"))
        assertFalse(seen.admit("a"))
        assertFalse(seen.admit("a"))
    }

    @Test
    fun `distinct ids are all admitted`() {
        val seen = SeenEvents()
        assertTrue(seen.admit("a"))
        assertTrue(seen.admit("b"))
        assertTrue(seen.admit("c"))
        assertEquals(3, seen.size())
    }

    @Test
    fun `the set is bounded and evicts the oldest`() {
        val seen = SeenEvents(capacity = 3)
        seen.admit("a")
        seen.admit("b")
        seen.admit("c")
        seen.admit("d")

        assertEquals(3, seen.size())
        // "a" fell out, so it looks new again. That is the accepted cost of a
        // bounded set: a very old duplicate can be delivered twice rather than
        // the process growing without limit.
        assertTrue(seen.admit("a"))
        assertFalse(seen.admit("d"))
    }
}
