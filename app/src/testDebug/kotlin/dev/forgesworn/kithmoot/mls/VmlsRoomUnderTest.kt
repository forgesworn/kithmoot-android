package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.CoordinationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a room shows while its persona's vault is held or fenced (D1 R3). */
class VmlsRoomUnderTest {
    @Test fun `an active or unknown vault leaves the room as it is`() {
        for (state in VmlsRoomState.entries) {
            assertNull(roomUnder(CoordinationStatus.Active, state))
            assertNull(roomUnder(null, state))
        }
    }

    @Test fun `a fenced vault stops a ready room, whatever it was doing`() {
        for (state in listOf(VmlsRoomState.READY, VmlsRoomState.SENDING, VmlsRoomState.RETRYING, VmlsRoomState.CHECKING, VmlsRoomState.JOINING, VmlsRoomState.STOPPED)) {
            val under = assertNotNull(roomUnder(CoordinationStatus.Fenced("witness-retired", null), state))
            assertEquals(VmlsRoomState.STOPPED, under.state)
            assertTrue("witness-retired" in under.reason!!)
        }
    }

    @Test fun `an unenrolled vault stops the room and a held one shows checking`() {
        assertEquals(VmlsRoomState.STOPPED, roomUnder(CoordinationStatus.NotEnrolled, VmlsRoomState.READY)?.state)
        assertEquals(VmlsRoomState.CHECKING, roomUnder(CoordinationStatus.Pending(refused = false), VmlsRoomState.READY)?.state)
        assertEquals(VmlsRoomState.CHECKING, roomUnder(CoordinationStatus.Pending(refused = true), VmlsRoomState.READY)?.state)
    }

    @Test fun `a room already ended or closing keeps what it says`() {
        for (state in listOf(VmlsRoomState.REMOVED, VmlsRoomState.LAPSED, VmlsRoomState.CLOSING)) {
            assertNull(roomUnder(CoordinationStatus.Fenced("x", null), state))
        }
    }
}
