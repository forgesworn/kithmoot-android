package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkConsentTest {
    private val account = "a".repeat(64)
    private val room = "b".repeat(64)
    private val node = "c".repeat(64)
    private val url = "ws://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaQ/events".lowercase()

    @Test fun `only active exact account room consent resolves a route`() {
        val vault = LinkConsentVault(MemoryStorage())
        val pending = consent(LinkConsentState.PENDING)
        vault.put(pending)

        assertNull(vault.activeRoute(account, room, url))
        vault.put(consent(LinkConsentState.ACTIVE))

        assertEquals("route-1", vault.activeRoute(account, room, url))
        assertNull(vault.activeRoute("d".repeat(64), room, url))
    }

    @Test fun `consent retains only the prior relay set`() {
        val storage = MemoryStorage()
        val vault = LinkConsentVault(storage)
        vault.put(consent(LinkConsentState.ACTIVE))

        val restored = LinkConsentVault(storage).all().single()

        assertEquals(listOf("wss://relay.example"), restored.previousRelays)
        assertEquals(LinkConsentState.ACTIVE, restored.state)
    }

    private fun consent(state: LinkConsentState) = LinkConsent(
        account, room, node, "route-1", url, listOf("wss://relay.example"), state,
    )

    private class MemoryStorage : RoomStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
