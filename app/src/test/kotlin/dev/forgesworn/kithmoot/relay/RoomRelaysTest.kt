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

    @Test fun theRoomsOwnRelaysComeFirstAndAreNeverCut() {
        val room = (1..8).map { "wss://room$it.example/" } + (1..8).map { "wss://op$it.example/" }
        val saved = (1..10).map { "wss://own$it.example" }
        val relays = RoomRelays.atOpen(saved, listOf(listOf("wss://linked.example/")), room = room)
        // Sixteen room relays fill the pool: this device's own are what the cap cuts.
        assertEquals(room, relays)
        val some = RoomRelays.atOpen(saved, emptyList(), room = room.take(8))
        assertEquals(room.take(8) + saved.take(8), some)
    }

    @Test fun aRoomRelayThisDeviceAlreadySavedKeepsItsSavedSpellingAndOpensOnce() {
        val relays = RoomRelays.atOpen(listOf("wss://own.example", "wss://shared.example"), emptyList(), room = listOf("wss://shared.example/"))
        assertEquals(listOf("wss://shared.example", "wss://own.example"), relays)
        assertEquals(setOf("wss://shared.example"), RoomRelays.ofRoom(relays, listOf("wss://shared.example/")))
        assertEquals(listOf("wss://new.example/"), RoomRelays.missing(relays, listOf("wss://shared.example/", "wss://new.example/")))
    }

    @Test fun aStaleLinkCannotDisplaceTheRoomsRelays() {
        // A bookmark names only B; the room was made on A.
        val relays = RoomRelays.atOpen(listOf("wss://b.example"), listOf(listOf("wss://b.example/")), room = listOf("wss://a.example/"))
        assertEquals(listOf("wss://a.example/", "wss://b.example"), relays)
    }
}
