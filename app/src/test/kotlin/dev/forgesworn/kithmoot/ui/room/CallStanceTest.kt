package dev.forgesworn.kithmoot.ui.room

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The call control's one opinion, checked against the web client's
 * `test/call-stance.test.ts` so the two implementations cannot drift.
 */
class CallStanceTest {

    @Test
    fun `an empty room offers a call to start`() {
        assertEquals(CallStance.START, callStance(mineOn = false, otherDevicesOn = 0, leaving = false))
        assertEquals("Start call", callStanceLabel(CallStance.START))
    }

    @Test
    fun `somebody else on a call is a call to join`() {
        assertEquals(CallStance.JOIN, callStance(mineOn = false, otherDevicesOn = 1, leaving = false))
        assertEquals("A call is on in this room", callStanceTitle(CallStance.JOIN))
    }

    @Test
    fun `being on it is a call to leave`() {
        assertEquals(CallStance.LEAVE, callStance(mineOn = true, otherDevicesOn = 2, leaving = false))
    }

    @Test
    fun `a leave in flight never paints the join door`() {
        // Our own entry is still on the roster while the leave settles. Read
        // naively that is "a call is on and you are not on it", which is the
        // join door - our own shadow, painted for a moment on the way out.
        assertEquals(CallStance.START, callStance(mineOn = true, otherDevicesOn = 0, leaving = true))
    }

    @Test
    fun `a leave from a call other people are still on offers to rejoin, not to start`() {
        assertEquals(CallStance.JOIN, callStance(mineOn = true, otherDevicesOn = 1, leaving = true))
    }
}
