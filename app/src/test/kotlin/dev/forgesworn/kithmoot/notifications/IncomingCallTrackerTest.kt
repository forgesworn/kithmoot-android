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
    fun `does not ring the callers other devices`() {
        val tracker = IncomingCallTracker()
        assertNull(tracker.update(call, "alice", false))
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
}
