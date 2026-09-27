package dev.forgesworn.kithmoot.relay

import org.junit.Test
import kotlin.test.*

class RoomRelaysTest {
    @Test fun aSavedRoomAlsoReadsTheRelaysItsLinksName() {
        // The saved copy lost a relay that the room's link, and so its history, still uses.
        val relays = RoomRelays.atOpen(
            listOf("wss://nos.lol", "wss://relay.primal.net"),
            listOf(listOf("wss://nos.lol/", "wss://relay.primal.net/", "wss://history.example/")),
        )
        assertEquals(listOf("wss://nos.lol", "wss://relay.primal.net", "wss://history.example/"), relays)
    }

    @Test fun savedRelaysStayFirstAndDuplicatesAcrossLinksCollapse() {
        val relays = RoomRelays.atOpen(
            listOf("wss://saved.example"),
            listOf(listOf("wss://A.example:443/", "wss://saved.example/"), listOf("wss://a.example")),
        )
        assertEquals(listOf("wss://saved.example", "wss://A.example:443/"), relays)
    }

    @Test fun invalidLinkRelaysAreSkippedAndTheTotalIsCapped() {
        val many = (1..40).map { "wss://r$it.example" }
        val relays = RoomRelays.atOpen(listOf("wss://saved.example"), listOf(listOf("https://not-a-relay", "not a url") + many))
        assertEquals(RoomRelays.MAX, relays.size)
        assertEquals("wss://saved.example", relays.first())
        assertFalse(relays.any { !it.startsWith("wss://") })
    }

    @Test fun theLegacyPublicPairGainsTheThirdFallback() {
        assertEquals(
            listOf("wss://nos.lol/", "wss://relay.primal.net", RoomRelays.PUBLIC_FALLBACK),
            RoomRelays.atOpen(listOf("wss://nos.lol/", "wss://relay.primal.net"), emptyList()),
        )
        // Anything other than exactly the pair is the room's own choice.
        assertEquals(listOf("wss://nos.lol"), RoomRelays.atOpen(listOf("wss://nos.lol"), emptyList()))
        assertEquals(
            listOf("wss://nos.lol", "wss://relay.primal.net", "wss://other.example"),
            RoomRelays.atOpen(listOf("wss://nos.lol", "wss://relay.primal.net", "wss://other.example"), emptyList()),
        )
    }
}
