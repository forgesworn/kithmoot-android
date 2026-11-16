package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.CircleGrantTerms
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class StandaloneWokConsentTest {
    private val account = "a".repeat(64)
    private val room = "b".repeat(64)
    private val url = "wss://relay.example.org/events"

    @Test fun `only an active or withdrawing exact consent resolves the standalone route`() {
        val vault = StandaloneWokConsentVault(MemoryStorage())
        vault.put(consent(StandaloneWokState.INSTALLING))
        assertNull(vault.activeRoute(account, room, url))

        vault.put(consent(StandaloneWokState.ACTIVE))
        assertEquals(url, vault.activeRoute(account, room, url))
        assertNull(vault.activeRoute("c".repeat(64), room, url))

        vault.put(consent(StandaloneWokState.WITHDRAWING))
        assertEquals(url, vault.activeRoute(account, room, url))
    }

    @Test fun `consent retains exact grants and the previous relay set`() {
        val storage = MemoryStorage()
        val plan = grantPlan()
        StandaloneWokConsentVault(storage).put(consent(StandaloneWokState.INSTALLING).copy(grants = listOf(plan)))

        val restored = StandaloneWokConsentVault(storage).all().single()
        assertEquals(listOf("wss://public.example.org"), restored.previousRelays)
        assertEquals(plan, restored.grants.single())
        assertEquals(StandaloneWokState.INSTALLING, restored.state)
    }

    @Test fun `non standalone and credential carrying endpoints are refused`() {
        for (bad in listOf("https://relay.example.org/events", "wss://relay.example.org/other", "wss://user@relay.example.org/events")) {
            assertFailsWith<IllegalArgumentException> { consent(StandaloneWokState.ACTIVE).copy(canonicalUrl = bad) }
        }
    }

    private fun consent(state: StandaloneWokState) = StandaloneWokConsent(
        account, room, url, listOf("wss://public.example.org"), state,
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
