package dev.forgesworn.kithmoot.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountProfileLookupTest {
    @Test
    fun `account lookup includes and deduplicates the public profile relays`() {
        val configured = listOf("wss://relay.primal.net", "wss://example.org")
        assertEquals(
            listOf("wss://relay.primal.net", "wss://example.org", "wss://purplepag.es", "wss://relay.damus.io"),
            accountProfileLookupRelays(configured, enabled = true),
        )
    }

    @Test
    fun `public profiles off asks no relay for the account profile`() {
        assertEquals(emptyList(), accountProfileLookupRelays(listOf("wss://example.org"), enabled = false))
    }
}
