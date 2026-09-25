package dev.forgesworn.kithmoot.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
