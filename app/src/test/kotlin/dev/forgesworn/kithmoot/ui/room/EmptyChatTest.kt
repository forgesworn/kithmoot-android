package dev.forgesworn.kithmoot.ui.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An empty Tor-only room with no relay reached says where its history comes from. */
class EmptyChatTest {
    @Test fun aTorOnlyRoomWithNoRelaySaysItsHistoryComesFromItsRelays() {
        val text = emptyChat(query = "", torOnly = true, relaysUp = 0)
        assertTrue(text.startsWith("This Tor-only room keeps no messages on this phone."))
        assertTrue("show once one answers" in text)
    }

    @Test fun anyOtherEmptyRoomIsSimplyEmpty() {
        assertEquals("Nothing said yet.", emptyChat(query = "", torOnly = true, relaysUp = 1))
        assertEquals("Nothing said yet.", emptyChat(query = "", torOnly = false, relaysUp = 0))
    }

    @Test fun aSearchWithNoHitsSaysSoInEveryRoom() {
        assertEquals("No matching messages.", emptyChat(query = "lab", torOnly = true, relaysUp = 0))
    }
}
