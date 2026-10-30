package dev.forgesworn.kithmoot.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileBatchTest {

    @Test fun `only authors not yet known or given up on are asked for`() {
        val authors = listOf("a", "b", "c", "d", "b")
        assertEquals(listOf("c"), profileAuthorsToAsk(authors, known = setOf("a", "b"), givenUp = setOf("d")))
        assertEquals(emptyList(), profileAuthorsToAsk(listOf("a"), known = setOf("a"), givenUp = emptySet()))
    }

    @Test fun `one more arrival asks for one author, not the whole room again`() {
        val room = (1..40).map { "p$it" }
        val known = room.toSet()
        assertEquals(listOf("p41"), profileAuthorsToAsk(room + "p41", known, emptySet()))
    }

    @Test fun `a batch never asks for more than 500 authors`() {
        assertEquals(500, profileAuthorsToAsk((1..900).map { "p$it" }, emptySet(), emptySet()).size)
    }

    @Test fun `one relay spelt with and without a trailing slash is one socket`() {
        assertEquals(listOf("wss://relay.primal.net/", "wss://purplepag.es"),
            distinctRelays(listOf("wss://relay.primal.net/", "wss://purplepag.es", "wss://relay.primal.net")))
    }
}
