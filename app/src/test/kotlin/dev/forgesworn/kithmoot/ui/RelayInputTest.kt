package dev.forgesworn.kithmoot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayInputTest {

    @Test
    fun `relays may be separated however the person pasted them`() {
        val parsed = parseRelays("wss://one.example\nwss://two.example, wss://three.example")

        assertEquals(listOf("wss://one.example", "wss://two.example", "wss://three.example"), parsed)
    }

    @Test
    fun `anything that is not a websocket URL is dropped`() {
        // A pasted https URL is a mistake worth ignoring rather than a relay
        // worth trying: the pool would sit retrying it forever.
        val parsed = parseRelays("https://relay.example\nrelay.example\nwss://good.example\n\n  ")

        assertEquals(listOf("wss://good.example"), parsed)
    }

    @Test
    fun `a repeated relay is only used once`() {
        assertEquals(listOf("ws://localhost:7777"), parseRelays("ws://localhost:7777, ws://localhost:7777"))
    }

    @Test
    fun `the defaults parse`() {
        assertEquals(DEFAULT_RELAYS, parseRelays(DEFAULT_RELAYS.joinToString("\n")))
        assertTrue(DEFAULT_RELAYS.isNotEmpty())
    }
}
