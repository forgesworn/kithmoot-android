package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneTest {
    @Test
    fun shelteredMeansTheCirclesOwnBoxAnOnionAloneIsPublic() {
        val onion = "wss://${"a".repeat(56)}.onion"
        val circle = setOf("wss://box.example", onion)
        assertEquals(Lane.SHELTERED, laneOfRelayUrl(onion, circle))
        assertEquals(Lane.SHELTERED, laneOfRelayUrl("wss://box.example/", circle))
        assertEquals(Lane.SHELTERED, laneOfRelayUrl("wss://BOX.example", circle))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("ws://xyz.onion:8080"))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("ws://xyz.onion:8080", circle))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("wss://relay.damus.io", circle))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("not a url", circle))
    }

    @Test
    fun distinctEndpointQueriesAndEscapedPathsCannotInheritTrust() {
        val circle = setOf("wss://box.example/drops?tenant=family", "wss://box.example/a%2Fb")
        assertEquals(Lane.SHELTERED, laneOfRelayUrl("wss://BOX.example:443/drops?tenant=family", circle))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("wss://box.example/drops?tenant=public", circle))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("wss://box.example/drops", circle))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("wss://box.example/a/b", circle))
        assertEquals(Lane.PUBLIC, laneOfRelayUrl("wss://user@box.example/drops?tenant=family", circle))
    }

    @Test
    fun aSetOfRelaysIsAsWeakAsItsWeakest() {
        val circle = setOf("wss://a.onion", "wss://b.onion")
        assertEquals(Lane.PUBLIC, laneOfRelays(listOf("wss://a.onion", "wss://relay.example"), circle))
        assertEquals(Lane.SHELTERED, laneOfRelays(listOf("wss://a.onion", "wss://b.onion"), circle))
        assertEquals(Lane.PUBLIC, laneOfRelays(listOf("wss://a.onion", "wss://b.onion")))
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
