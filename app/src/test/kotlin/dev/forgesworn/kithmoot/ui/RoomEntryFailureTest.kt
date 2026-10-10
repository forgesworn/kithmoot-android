package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.account.SignerException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomEntryFailureTest {
    @Test
    fun `a signer refusal remains actionable at room entry`() {
        assertEquals(
            "Cambium declined to sign.",
            roomEntryFailureMessage(SignerException("Cambium declined to sign.")),
        )
    }

    @Test
    fun `an unknown failure does not expose implementation detail`() {
        assertEquals(
            "The room could not be opened. Try again.",
            roomEntryFailureMessage(IllegalStateException("private detail")),
        )
    }

    @Test fun `entry diagnostics contain code locations but no private messages`() {
        val nested = IllegalArgumentException("private signer bearer and source bytes")
        val failure = IllegalStateException("private saved data", nested)
        val diagnostic = roomEntryFailureDiagnostic(failure)
        assertTrue(diagnostic.contains("java.lang.IllegalStateException"))
        assertTrue(diagnostic.contains("java.lang.IllegalArgumentException"))
        assertTrue(diagnostic.contains("RoomEntryFailureTest"))
        assertFalse(diagnostic.contains("private saved data"))
        assertFalse(diagnostic.contains("private signer bearer and source bytes"))
        assertFalse(diagnostic.contains("signer bearer"))
        assertEquals("The room could not be opened. Try again.", roomEntryFailureMessage(failure))
    }

    @Test fun `cyclic causes cannot produce unbounded diagnostics`() {
        val one = IllegalStateException("private one")
        val two = IllegalArgumentException("private two", one)
        one.initCause(two)
        val diagnostic = roomEntryFailureDiagnostic(one)
        assertEquals(4, diagnostic.split(" <- ").size)
        assertFalse(diagnostic.contains("private"))
    }
}
