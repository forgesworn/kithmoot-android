package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.session.RoomEpochState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What the open room says as a secure update moves it, and what it does not say for a scheduled one. */
class EpochProgressTest {
    private val room = RoomState(notice = "earlier")

    @Test fun `a removal or a catch-up is announced either side`() {
        val updating = room.withEpochProgress(RoomEpochState.Updating(2))
        assertEquals("Secure room update in progress.", updating.notice)
        assertEquals("updating", updating.roomUpdate)
        assertEquals(2, updating.movedOn)
        val done = updating.withEpochProgress(RoomEpochState.Active(2, "e".repeat(64)))
        assertEquals("Secure room update complete.", done.notice)
        assertNull(done.roomUpdate)
        assertNull(done.movedOn)
    }

    @Test fun `a scheduled turn of the key says nothing either side`() {
        val updating = room.withEpochProgress(RoomEpochState.Updating(2, scheduled = true))
        assertSame(room, updating)
        val done = updating.withEpochProgress(RoomEpochState.Active(2, "e".repeat(64), scheduled = true))
        assertEquals("earlier", done.notice)
        assertNull(done.roomUpdate)
        assertNull(done.movedOn)
    }

    @Test fun `a scheduled turn waiting on Bothy still shows the panel that holds the retry`() {
        val quiet = room.copy(cadence = CadenceViewState(eligible = true, state = "active"))
        val updating = quiet.withEpochProgress(RoomEpochState.Updating(2, scheduled = true))
        assertEquals("updating", updating.roomUpdate)
        assertEquals("Secure room update is waiting for Bothy to retire the old schedule.", updating.notice)
        // Having said it was waiting, it says when it is done.
        assertEquals("Secure room update complete.", updating.withEpochProgress(RoomEpochState.Active(2, "e".repeat(64), scheduled = true)).notice)
    }
}
