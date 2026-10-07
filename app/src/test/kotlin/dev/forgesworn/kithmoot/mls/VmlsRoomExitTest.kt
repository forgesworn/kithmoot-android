package dev.forgesworn.kithmoot.mls

import kotlin.test.Test
import kotlin.test.assertEquals

/** D1 R1: a keeper's only way out of a room is the close that revokes its guests' grants, whatever the room's state. */
class VmlsRoomExitTest {
    private fun room(keeper: Boolean, state: VmlsRoomState) =
        VmlsRoomView("aa".repeat(32), "Room", "bb".repeat(32), "Box", keeper, state, null, emptyList(), false, false, emptyList())

    @Test fun `a keeper closes in every state, removed or lapsed included`() {
        for (state in VmlsRoomState.entries) assertEquals(VmlsRoomExit.CLOSE, room(keeper = true, state).exit, "$state")
    }

    @Test fun `a guest forgets a room that ended and leaves one that has not`() {
        assertEquals(VmlsRoomExit.FORGET, room(keeper = false, VmlsRoomState.REMOVED).exit)
        assertEquals(VmlsRoomExit.FORGET, room(keeper = false, VmlsRoomState.LAPSED).exit)
        assertEquals(VmlsRoomExit.LEAVE, room(keeper = false, VmlsRoomState.READY).exit)
        assertEquals(VmlsRoomExit.LEAVE, room(keeper = false, VmlsRoomState.STOPPED).exit)
    }
}
