package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.account.SignerException
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
