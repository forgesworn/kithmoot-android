package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneTest {
    @Test
    fun onionIsShelteredEverythingElsePublic() {
        assertEquals(Lane.SHELTERED, laneOfRelayUrl("wss://${"a".repeat(56)}.onion"))
        assertEquals(Lane.SHELTERED, laneOfRelayUrl("ws://xyz.onion:8080"))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("wss://relay.damus.io"))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("wss://onion.example.com"))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("not a url"))
    }

    @Test
    fun aSetOfRelaysIsAsWeakAsItsWeakest() {
        assertEquals(Lane.PUBLIC, laneOfRelays(listOf("wss://a.onion", "wss://relay.example")))
        assertEquals(Lane.SHELTERED, laneOfRelays(listOf("wss://a.onion", "wss://b.onion")))
        assertNull(laneOfRelays(emptyList()))
        assertEquals(Lane.SHELTERED, weakestLane(listOf(Lane.DIRECT, Lane.SHELTERED)))
    }

    @Test
    fun downgradeIsWeakerThanAskedFor() {
        assertTrue(isDowngrade(Lane.SHELTERED, Lane.PUBLIC))
        assertTrue(isDowngrade(Lane.DIRECT, Lane.SHELTERED))
        assertFalse(isDowngrade(Lane.PUBLIC, Lane.SHELTERED))
        assertFalse(isDowngrade(Lane.PUBLIC, Lane.PUBLIC))
    }

    @Test
    fun everyStateReadsWithoutColour() {
        for (lane in Lane.entries) {
            assertTrue(lane.chip.startsWith(lane.glyph))
            assertTrue(lane.meaning.length > 10)
        }
    }
}
