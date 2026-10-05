package dev.forgesworn.kithmoot.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from app/src/incoming-call.test.ts in the web client. */
class IncomingCallTrackerTest {
    private val call = IncomingCall("call-a", "alice")

    @Test
    fun `rings every recipient device once for a new call`() {
        val phone = IncomingCallTracker()
        val laptop = IncomingCallTracker()
        assertEquals(IncomingCallChange.Ring(call), phone.update(call, "bob", false))
        assertEquals(IncomingCallChange.Ring(call), laptop.update(call, "bob", false))
        assertNull(phone.update(call, "bob", false))
        assertNull(laptop.update(call, "bob", false))
    }

    @Test
    fun `does not ring the callers other devices but shows them a quiet notice once`() {
        val tracker = IncomingCallTracker()
        assertEquals(IncomingCallChange.OwnCallElsewhere(call), tracker.update(call, "alice", false))
        assertNull(tracker.update(call, "alice", false))
    }

    @Test
    fun `another persons call still rings`() {
        val tracker = IncomingCallTracker()
        assertEquals(IncomingCallChange.Ring(call), tracker.update(call, "bob", false))
    }

    @Test
    fun `no notice for an own call this device has joined`() {
        val tracker = IncomingCallTracker()
        assertNull(tracker.update(call, "alice", true))
        assertNull(tracker.update(call, "alice", false))
    }

    @Test
    fun `the notice comes down when this device joins or the call ends`() {
        val joining = IncomingCallTracker()
        joining.update(call, "alice", false)
        assertEquals(IncomingCallChange.OwnCallElsewhereStop, joining.update(call, "alice", true))
        assertNull(joining.update(call, "alice", false))

        val ending = IncomingCallTracker()
        ending.update(call, "alice", false)
        assertEquals(IncomingCallChange.OwnCallElsewhereStop, ending.update(null, "alice", false))
        assertNull(ending.update(null, "alice", false))
    }

    @Test
    fun `an own call and another persons call replace each other`() {
        val tracker = IncomingCallTracker()
        val friends = IncomingCall("call-b", "carol")
        assertEquals(IncomingCallChange.OwnCallElsewhere(call), tracker.update(call, "alice", false))
        // The ring replaces the notice, so the one stop that follows is the ring's.
        assertEquals(IncomingCallChange.Ring(friends), tracker.update(friends, "alice", false))
        assertEquals(IncomingCallChange.Stop, tracker.update(null, "alice", false))

        val next = IncomingCall("call-c", "alice")
        tracker.update(IncomingCall("call-d", "carol"), "alice", false)
        assertEquals(IncomingCallChange.OwnCallElsewhere(next), tracker.update(next, "alice", false))
        assertEquals(IncomingCallChange.OwnCallElsewhereStop, tracker.update(null, "alice", false))
    }

    @Test
    fun `returning to an own call already seen stops a ring for another call`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, "alice", false)
        tracker.update(IncomingCall("call-b", "carol"), "alice", false)
        assertEquals(IncomingCallChange.Stop, tracker.update(call, "alice", false))
        assertNull(tracker.update(call, "alice", false))
    }

    @Test
    fun `reset takes the notice down and lets it show again`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, "alice", false)
        assertEquals(IncomingCallChange.OwnCallElsewhereStop, tracker.reset())
        assertEquals(IncomingCallChange.OwnCallElsewhere(call), tracker.update(call, "alice", false))
    }

    @Test
    fun `stops when this device joins or the call ends`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, "bob", false)
        assertEquals(IncomingCallChange.Stop, tracker.update(call, "bob", true))
        assertNull(tracker.update(call, "bob", false))

        val other = IncomingCall("call-b", "alice")
        assertEquals(IncomingCallChange.Ring(other), tracker.update(other, "bob", false))
        assertEquals(IncomingCallChange.Stop, tracker.update(null, "bob", false))
    }

    @Test
    fun `reset lets a newly opened room ring for its current call`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, "bob", false)
        assertEquals(IncomingCallChange.Stop, tracker.reset())
        assertEquals(IncomingCallChange.Ring(call), tracker.update(call, "bob", false))
    }

    @Test
    fun `a call handled under one tracker stays handled under the next`() {
        // The room's ringing moves from the open room's tracker back to the
        // background listener's when KithMoot closes mid-call.
        HandledCalls.add("room-1", "call-a")
        assertTrue(HandledCalls.contains("room-1", "call-a"))
        assertFalse(HandledCalls.contains("room-2", "call-a"))
        assertFalse(HandledCalls.contains("room-1", "call-b"))
        val background = IncomingCallTracker()
        assertNull(background.update(call, "bob", HandledCalls.contains("room-1", call.id)))
    }

    @Test
    fun `handled calls keep only the most recent`() {
        repeat(100) { HandledCalls.add("room-x", "call-$it") }
        assertFalse(HandledCalls.contains("room-x", "call-0"))
        assertTrue(HandledCalls.contains("room-x", "call-99"))
    }

    @Test
    fun `a room this device was just on a call in stays quiet for a minute`() {
        HandledCalls.onCall("room-q", 1_000L)
        assertTrue(HandledCalls.justOnCall("room-q", 1_000L + QUIET_AFTER_CALL_MILLIS - 1))
        assertFalse(HandledCalls.justOnCall("room-q", 1_000L + QUIET_AFTER_CALL_MILLIS))
        assertFalse(HandledCalls.justOnCall("room-other", 1_000L))
    }
}
