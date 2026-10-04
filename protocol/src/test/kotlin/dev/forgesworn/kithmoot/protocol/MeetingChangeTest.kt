package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The host's changes to a meeting policy, as `withSpeaker` and
 *  `withMeetingMode` make them in the web client's `src/meeting.test.ts`. */
class MeetingChangeTest {
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)

    @Test fun `every change outranks the policy it changes`() {
        val start = MeetingPolicy(false, emptyList(), 1_000)
        val on = withMeetingMode(start, true, 500)
        assertEquals(MeetingPolicy(true, emptyList(), 1_001), on)
        val withAlice = withSpeaker(on, alice.uppercase(), true, 2_000)
        assertEquals(MeetingPolicy(true, listOf(alice), 2_000), withAlice)
        assertEquals(MeetingPolicy(true, emptyList(), 2_001), withSpeaker(withAlice, alice, false, 2_000))
    }

    @Test fun `speakers stay canonical, and switching off keeps them`() {
        val policy = withSpeaker(withSpeaker(MeetingPolicy(true, emptyList(), 1), bob, true, 5), alice, true, 5)
        assertEquals(listOf(alice, bob), policy.speakers)
        assertEquals("made a speaker twice, listed once", policy.speakers, withSpeaker(policy, alice, true, 5).speakers)
        assertEquals(MeetingPolicy(false, listOf(alice, bob), 7), withMeetingMode(policy, false, 0))
        assertThrows(IllegalArgumentException::class.java) { withSpeaker(policy, "nobody", true, 9) }
    }
}
