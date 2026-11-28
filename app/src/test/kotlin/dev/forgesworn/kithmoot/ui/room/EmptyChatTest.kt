package dev.forgesworn.kithmoot.ui.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An empty chat with no relay reached is not called empty; a Tor-only one says where its history comes from. */
class EmptyChatTest {
    @Test fun aTorOnlyRoomWithNoRelaySaysItsHistoryComesFromItsRelays() {
        val text = emptyChat(query = "", torOnly = true, relaysUp = 0)
        assertTrue(text.startsWith("This Tor-only room keeps no messages on this phone."))
        assertTrue("show once one answers" in text)
    }

    @Test fun aConnectedRoomWithNoMessagesIsSimplyEmpty() {
        assertEquals("Nothing said yet.", emptyChat(query = "", torOnly = true, relaysUp = 1))
        assertEquals("Nothing said yet.", emptyChat(query = "", torOnly = false, relaysUp = 2))
    }

    @Test fun aRoomStillConnectingIsNotCalledEmpty() {
        // A node room said "Nothing said yet." for 14 seconds, then filled with its history.
        assertEquals("Messages show once the room connects.", emptyChat(query = "", torOnly = false, relaysUp = 0))
    }

    @Test fun aNearbyRoomHasNoRelaysToWaitFor() {
        assertEquals("Nothing said yet.", emptyChat(query = "", torOnly = false, relaysUp = 0, nearby = true))
    }

    @Test fun aSearchWithNoHitsSaysSoInEveryRoom() {
        assertEquals("No matching messages.", emptyChat(query = "lab", torOnly = true, relaysUp = 0))
    }
}
