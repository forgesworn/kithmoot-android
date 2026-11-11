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
    fun `a call rings for a minute from when it started, and an older one not at all`() {
        val now = 1_000_000L
        assertEquals(RING_SECONDS, ringSecondsFor(now, now))
        assertEquals(RING_SECONDS - 20, ringSecondsFor(now - 20, now))
        assertEquals(1L, ringSecondsFor(now - (RING_SECONDS - 1), now))
        assertEquals(0L, ringSecondsFor(now - RING_SECONDS, now))
        assertEquals(0L, ringSecondsFor(now - 3_600, now))
        // A caller whose clock runs ahead has just started.
        assertEquals(RING_SECONDS, ringSecondsFor(now + 30, now))
    }

    @Test
    fun `rings every recipient device once for a new call`() {
        val phone = IncomingCallTracker()
        val laptop = IncomingCallTracker()
        assertEquals(IncomingCallChange.Ring(call), phone.update(call, false))
        assertEquals(IncomingCallChange.Ring(call), laptop.update(call, false))
        assertNull(phone.update(call, false))
        assertNull(laptop.update(call, false))
    }

    @Test
    fun `a call one of a person's devices is on rings none of their others, and answering on one stops the rest`() {
        // The caller's own other devices see their person on the call: joined.
        val callersPhone = IncomingCallTracker()
        assertNull(callersPhone.update(call, true))
        assertNull(callersPhone.update(call, true))
        // The person called rings on both devices until one of them answers,
        // and then neither rings again for that call.
        val phone = IncomingCallTracker()
        val laptop = IncomingCallTracker()
        assertEquals(IncomingCallChange.Ring(call), phone.update(call, false))
        assertEquals(IncomingCallChange.Ring(call), laptop.update(call, false))
        assertEquals(IncomingCallChange.Stop, laptop.update(call, true))
        assertEquals(IncomingCallChange.Stop, phone.update(call, true))
        assertNull(phone.update(call, false))
    }

    @Test
    fun `an answer elsewhere stops only the call it names`() {
        val tracker = IncomingCallTracker()
        val friends = IncomingCall("call-b", "carol")
        assertEquals(IncomingCallChange.Ring(friends), tracker.update(friends, false))
        assertNull(tracker.answeredElsewhere(call.id))
        assertEquals(IncomingCallChange.Stop, tracker.answeredElsewhere(friends.id))
        assertNull(tracker.update(friends, false))
    }

    @Test
    fun `an answer elsewhere that arrives before its start keeps the start from ringing`() {
        // Bells are separate events; nothing promises the start comes first.
        val tracker = IncomingCallTracker()
        assertNull(tracker.answeredElsewhere(call.id))
        assertNull(tracker.update(call, false))
    }

    @Test
    fun `another persons call still rings`() {
        val tracker = IncomingCallTracker()
        assertEquals(IncomingCallChange.Ring(call), tracker.update(call, false))
    }

    @Test
    fun `no notice for an own call this device has joined`() {
        val tracker = IncomingCallTracker()
        assertNull(tracker.update(call, true))
        assertNull(tracker.update(call, false))
    }

    @Test
    fun `the ring stops when this device joins or the call ends`() {
        val joining = IncomingCallTracker()
        joining.update(call, false)
        assertEquals(IncomingCallChange.Stop, joining.update(call, true))
        assertNull(joining.update(call, false))

        val ending = IncomingCallTracker()
        ending.update(call, false)
        assertEquals(IncomingCallChange.Stop, ending.update(null, false))
        assertNull(ending.update(null, false))
    }

    @Test
    fun `own and other calls each ring once`() {
        val tracker = IncomingCallTracker()
        val friends = IncomingCall("call-b", "carol")
        assertEquals(IncomingCallChange.Ring(call), tracker.update(call, false))
        // The ring replaces the notice, so the one stop that follows is the ring's.
        assertEquals(IncomingCallChange.Ring(friends), tracker.update(friends, false))
        assertEquals(IncomingCallChange.Stop, tracker.update(null, false))

        val next = IncomingCall("call-c", "alice")
        tracker.update(IncomingCall("call-d", "carol"), false)
        assertEquals(IncomingCallChange.Ring(next), tracker.update(next, false))
        assertEquals(IncomingCallChange.Stop, tracker.update(null, false))
    }

    @Test
    fun `returning to an own call already seen stops a ring for another call`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, false)
        tracker.update(IncomingCall("call-b", "carol"), false)
        assertEquals(IncomingCallChange.Stop, tracker.update(call, false))
        assertNull(tracker.update(call, false))
    }

    @Test
    fun `reset stops the ring and permits it again`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, false)
        assertEquals(IncomingCallChange.Stop, tracker.reset())
        assertEquals(IncomingCallChange.Ring(call), tracker.update(call, false))
    }

    @Test
    fun `stops when this device joins or the call ends`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, false)
        assertEquals(IncomingCallChange.Stop, tracker.update(call, true))
        assertNull(tracker.update(call, false))

        val other = IncomingCall("call-b", "alice")
        assertEquals(IncomingCallChange.Ring(other), tracker.update(other, false))
        assertEquals(IncomingCallChange.Stop, tracker.update(null, false))
    }

    @Test
    fun `reset lets a newly opened room ring for its current call`() {
        val tracker = IncomingCallTracker()
        tracker.update(call, false)
        assertEquals(IncomingCallChange.Stop, tracker.reset())
        assertEquals(IncomingCallChange.Ring(call), tracker.update(call, false))
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
        assertNull(background.update(call, HandledCalls.contains("room-1", call.id)))
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
