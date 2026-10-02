package dev.forgesworn.kithmoot.epoch

import kotlin.test.Test
import kotlin.test.assertEquals

class EpochOpeningTest {
    @Test fun `told the room is ahead, ask its authority before saying anything`() {
        assertEquals(EpochOpening.Recover(1), epochOpening(hint = 1, current = 0, follows = true, isAuthority = false, mayProbe = true))
        assertEquals(EpochOpening.Recover(4), epochOpening(hint = 4, current = 2, follows = true, isAuthority = false, mayProbe = false))
    }

    @Test fun `told nothing newer, ask quietly once where that is allowed`() {
        assertEquals(EpochOpening.Probe, epochOpening(hint = null, current = 0, follows = true, isAuthority = false, mayProbe = true))
        assertEquals(EpochOpening.Probe, epochOpening(hint = 0, current = 0, follows = true, isAuthority = false, mayProbe = true))
        assertEquals(EpochOpening.Probe, epochOpening(hint = 1, current = 3, follows = true, isAuthority = false, mayProbe = true))
        assertEquals(EpochOpening.None, epochOpening(hint = null, current = 0, follows = true, isAuthority = false, mayProbe = false))
    }

    @Test fun `nobody to ask, nothing is asked`() {
        // A legacy link pins no authority; a session without a gate cannot commit what it would hear.
        assertEquals(EpochOpening.None, epochOpening(hint = 5, current = 0, follows = false, isAuthority = false, mayProbe = true))
        // The authority answers requests; it never asks itself.
        assertEquals(EpochOpening.None, epochOpening(hint = 5, current = 0, follows = true, isAuthority = true, mayProbe = true))
    }
}
