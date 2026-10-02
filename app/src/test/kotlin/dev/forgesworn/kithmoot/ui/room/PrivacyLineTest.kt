package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.protocol.laneOfRelays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The line above a room's messages agrees with the chip on each message. The
 * 29 September anonymous run found a connected Tor-only room saying "checking
 * connection" while every message it showed said "○ public".
 */
class PrivacyLineTest {
    private val onion = "wss://" + "a".repeat(56) + ".onion"
    private val circleBox = "wss://box.example"

    @Test fun aTorOnlyRoomNamesItsLaneAndItsCarrier() {
        val lane = roomLane(listOf(onion), torOnly = true) { emptySet() }
        assertEquals(Lane.PUBLIC, lane)
        assertEquals("Encrypted · public relays · Tor only", privacyLine(lane, torOnly = true, quiet = false))
        assertEquals("${Lane.PUBLIC.meaning} $TOR_ONLY_MEANING", privacyMeaning(lane, torOnly = true))
    }

    @Test fun theHeaderReadsByTheRuleEachMessageIsLabelledBy() {
        // A Tor-only room's transport reads with no circle, so its messages are
        // public even when the account has marked the onion as a circle box.
        val messageLane = laneOfRelays(listOf(onion), emptySet())
        assertEquals(messageLane, roomLane(listOf(onion), torOnly = true) { setOf(onion) })
        assertEquals(Lane.SHELTERED, roomLane(listOf(onion), torOnly = false) { setOf(onion) })
        assertEquals(Lane.SHELTERED, roomLane(listOf(circleBox), torOnly = false) { setOf(circleBox) })
    }

    @Test fun otherRoomsReadAsBefore() {
        assertEquals("Encrypted · public relays", privacyLine(Lane.PUBLIC, torOnly = false, quiet = false))
        assertEquals("Encrypted · circle relays · quiet", privacyLine(Lane.SHELTERED, torOnly = false, quiet = true))
        assertEquals("Encrypted · direct", privacyLine(Lane.DIRECT, torOnly = false, quiet = false))
        assertEquals("Encrypted · checking connection", privacyLine(null, torOnly = false, quiet = false))
        assertEquals(Lane.SHELTERED.meaning, privacyMeaning(Lane.SHELTERED, torOnly = false))
        assertNull(privacyMeaning(null, torOnly = true))
        assertNull(roomLane(emptyList(), torOnly = true) { emptySet() })
    }
}
