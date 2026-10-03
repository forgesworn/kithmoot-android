package dev.forgesworn.kithmoot.ui.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account entry point says what the open room does with an account. */
class AccountMenuTextTest {
    @Test fun aSignedInAccountIsMarkedUnusedOnlyInsideATorOnlyRoom() {
        assertEquals("Not used in this Tor-only room", accountNotInUse(inRoom = true, torOnlyRoom = true))
        assertNull(accountNotInUse(inRoom = true, torOnlyRoom = false))
        assertNull(accountNotInUse(inRoom = false, torOnlyRoom = true))
    }

    @Test fun accountActionsThatReachTheAccountsRelaysAreNotOfferedInsideATorOnlyRoom() {
        assertFalse(accountActionsOffered(inRoom = true, torOnlyRoom = true))
        assertTrue(accountActionsOffered(inRoom = true, torOnlyRoom = false))
        assertTrue(accountActionsOffered(inRoom = false, torOnlyRoom = false))
        // Outside a room nothing is Tor-only, whatever the last room was.
        assertTrue(accountActionsOffered(inRoom = false, torOnlyRoom = true))
    }

    @Test fun signingInFromATorOnlyRoomSaysTheRoomNeverUsesAnAccount() {
        val torOnly = signInFromRoom(torOnlyRoom = true)
        assertTrue(torOnly.startsWith("This Tor-only room never uses an account"))
        // Tor-only rooms have no calls, so leaving one ends none.
        assertFalse("call" in torOnly)
        assertTrue("any call" in signInFromRoom(torOnlyRoom = false))
    }
}
