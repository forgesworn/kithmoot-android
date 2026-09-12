package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.CircleGrantTerms
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
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

    @Test fun `consent retains exact signed grant and rollback events`() {
        val storage = MemoryStorage()
        val plan = grantPlan()
        LinkConsentVault(storage).put(consent(LinkConsentState.PENDING).copy(grants = listOf(plan), grantsRevoked = true))

        val restored = LinkConsentVault(storage).all().single().grants.single()

        assertEquals(plan, restored)
        assertEquals(true, LinkConsentVault(storage).all().single().grantsRevoked)
    }

    @Test fun `intermediate commit states never resolve a route and survive restart`() {
        for (state in listOf(LinkConsentState.ACTIVATING, LinkConsentState.WITHDRAWING, LinkConsentState.RETIRED)) {
            val storage = MemoryStorage()
            LinkConsentVault(storage).put(consent(state))
            val restored = LinkConsentVault(storage)
            assertEquals(state, restored.all().single().state)
            assertNull(restored.activeRoute(account, room, url))
        }
    }

    @Test fun `revocation in progress retains the active local route`() {
        val vault = LinkConsentVault(MemoryStorage())
        vault.put(consent(LinkConsentState.REVOKING).copy(grants = listOf(grantPlan())))

        assertEquals("route-1", vault.activeRoute(account, room, url))
    }

    private fun consent(state: LinkConsentState) = LinkConsent(
        account, room, node, "route-1", url, listOf("wss://relay.example"), state,
    )

    private fun grantPlan(): CircleGrantPlan {
        val key = Entropy.bytes(32)
        val terms = CircleGrantTerms(url, room, "d".repeat(64), "e".repeat(64), Entropy.bytes(16).toHex(), 1_900_000_000)
        return CircleGrantPlan(
            Events.sign(key, KIND_CIRCLE_EVENT_GRANT, 1_800_000_000, terms.tags(CircleGrantStatus.ACTIVE), ""),
            Events.sign(key, KIND_CIRCLE_EVENT_GRANT, 1_800_000_001, terms.tags(CircleGrantStatus.REVOKED), ""),
        )
    }

    private class MemoryStorage : RoomStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
