package dev.forgesworn.kithmoot.ui

import kotlin.test.*

class GuestAdmissionGateTest {
    @Test fun `opening an invitation does not own a request until the guest acts`() {
        val gate = GuestAdmissionGate()
        gate.prepare("synthetic-invitation", "Workshop", "Rowan")
        assertEquals(GuestAdmissionPhase.PREVIEW, gate.view.value!!.phase)
        gate.editName("  Reviewed name  ")
        val attempt = gate.request()!!
        assertEquals("Reviewed name", attempt.name)
        assertTrue(gate.isCurrent(attempt))
        assertNull(gate.request(), "Repeated taps must not own a second exchange")
    }

    @Test fun `name and room survive cancellation but a late grant cannot enter`() {
        val gate = GuestAdmissionGate()
        gate.prepare("synthetic-invitation", "Workshop", "Keep my name")
        val old = gate.request()!!
        gate.cancel()
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.phase(old, GuestAdmissionPhase.ADMITTED))
        assertEquals("Keep my name", gate.view.value!!.name)
        assertTrue(gate.retry())
        assertEquals(GuestAdmissionPhase.PREVIEW, gate.view.value!!.phase)
        assertFalse(gate.isCurrent(old))
        val next = gate.request()!!
        assertNotEquals(old.id, next.id)
        assertFalse(gate.finish(old))
        assertTrue(gate.isCurrent(next))
    }

    @Test fun `refusal expiry and unavailable are distinct terminal states requiring review on retry`() {
        for (phase in listOf(GuestAdmissionPhase.DECLINED, GuestAdmissionPhase.EXPIRED, GuestAdmissionPhase.UNAVAILABLE)) {
            val gate = GuestAdmissionGate()
            gate.prepare("synthetic-invitation", "Workshop", "Rowan")
            val old = gate.request()!!
            assertTrue(gate.phase(old, phase, "Explanation"))
            assertEquals(phase, gate.view.value!!.phase)
            assertEquals("Explanation", gate.view.value!!.detail)
            assertNull(gate.request())
            assertFalse(gate.isCurrent(old))
            assertTrue(gate.retry())
            assertEquals(GuestAdmissionPhase.PREVIEW, gate.view.value!!.phase)
            assertNull(gate.view.value!!.detail)
        }
    }

    @Test fun `replacing the invitation prevents old signer and transport callbacks changing it`() {
        val gate = GuestAdmissionGate()
        gate.prepare("first-synthetic-invitation", "First room", "Rowan")
        val old = gate.request()!!
        gate.prepare("second-synthetic-invitation", "Second room", "Bo")
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.phase(old, GuestAdmissionPhase.WAITING))
        assertFalse(gate.finish(old))
        assertEquals("Second room", gate.view.value!!.roomName)
        assertEquals("Bo", gate.view.value!!.name)
        assertEquals(GuestAdmissionPhase.PREVIEW, gate.view.value!!.phase)
    }

    @Test fun `admission does not retire the guard before entry commits`() {
        val gate = GuestAdmissionGate()
        gate.prepare("synthetic-invitation", "Workshop", "Rowan")
        val attempt = gate.request()!!
        assertTrue(gate.phase(attempt, GuestAdmissionPhase.ADMITTED))
        assertTrue(gate.isCurrent(attempt))
        assertTrue(gate.finish(attempt))
        assertFalse(gate.isCurrent(attempt))
        assertNull(gate.view.value)
    }

    @Test fun `clearing entry retires callbacks and does not retain an invitation for retry`() {
        val gate = GuestAdmissionGate()
        gate.prepare("synthetic-invitation", "Workshop", "Rowan")
        val attempt = gate.request()!!
        gate.clear()
        assertFalse(gate.isCurrent(attempt))
        assertNull(gate.view.value)
        assertFalse(gate.retry())
        assertNull(gate.request())
    }
}
