package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.SpryDescriptor
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.protocol.standard
import dev.forgesworn.kithmoot.ui.MemberRelayWords
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A guest and another member's built-in relay (a standard-profile Spry node
 * reached over `spry:` control): the rules of the web's
 * `app/src/spry-room-standard.test.ts`, without a room.
 */
class SpryMemberRelayTest {
    private val now = System.currentTimeMillis() / 1000
    private val nodeKey = ByteArray(32) { 1 }
    private val endpoint = "spry://${Schnorr.publicKeyHex(nodeKey)}?relay=wss%3A%2F%2Frelay.example%2F"
    private val ownEndpoint = "spry://${Schnorr.publicKeyHex(ByteArray(32) { 2 })}?relay=wss%3A%2F%2Frelay.example%2F"
    private val host = "a1".repeat(32)
    private val guest = "b2".repeat(32)
    private val other = "c3".repeat(32)

    private fun card(content: String) = Events.sign(nodeKey, 24183, now, listOf(listOf("expiration", (now + 600).toString())), content)
    private fun offer(node: dev.forgesworn.kithmoot.protocol.NostrEvent? = card("{\"profile\":\"spry-standard-v1\",\"v\":1}"), at: String = endpoint) =
        SpryOffer(at, "a".repeat(43), now + 86_400, node)
    private fun descriptor(participant: String, spry: SpryOffer, device: String = participant) =
        SpryDescriptor(spry, participant, now, JsonArray(emptyList()), JsonArray(emptyList()), device)

    @Test fun `a standard card signed by the endpoint key marks a member's relay, a peer card never does`() {
        assertTrue(offer().standard)
        assertFalse(offer(card("{\"address\":\"[fd12:3456::2]:3745\",\"profile\":\"spry-peer-v1\",\"v\":1}")).standard)
        assertFalse(offer(null).standard)
    }

    @Test fun `another member's spry offer is foreign, this device's own node and https offers are not`() {
        assertTrue(spryForeignControl(offer(), null))
        assertTrue(spryForeignControl(offer(), ownEndpoint))
        assertFalse(spryForeignControl(offer(at = ownEndpoint), ownEndpoint))
        assertFalse(spryForeignControl(offer(null, "https://relay.example"), null))
    }

    @Test fun `a member's relay is usable only from a standard node and only once allowed`() {
        assertTrue(spryOfferUsable(offer(), null) { true })
        assertFalse(spryOfferUsable(offer(), null) { false })
        // A private mesh node is never contacted from another device, allowed or not.
        assertFalse(spryOfferUsable(offer(card("{\"address\":\"[fd12:3456::2]:3745\",\"profile\":\"spry-peer-v1\",\"v\":1}")), null) { true })
        // This device's own node, and an https offer, need no choice.
        assertTrue(spryOfferUsable(offer(null, ownEndpoint), ownEndpoint) { false })
        assertTrue(spryOfferUsable(offer(null, "https://relay.example"), null) { false })
    }

    @Test fun `the host is named only when exactly one participant publishes the relay`() {
        assertEquals(host, spryOfferHost(listOf(descriptor(host, offer())), endpoint))
        // Two devices of one person still name one host.
        assertEquals(host, spryOfferHost(listOf(descriptor(host, offer(), "d1"), descriptor(host, offer(), "d2")), endpoint))
        assertNull(spryOfferHost(listOf(descriptor(host, offer()), descriptor(other, offer())), endpoint))
        assertNull(spryOfferHost(listOf(descriptor(host, offer(null, "https://relay.example"))), endpoint))
        assertNull(spryOfferHost(emptyList(), endpoint))
    }

    @Test fun `with Hide my IP address on, a member's relay waits for a choice naming them`() {
        // Without Hide my IP address the address is in the call's candidates anyway.
        assertTrue(memberRelayAllowed(relayOnly = false, self = guest, host = null, consented = emptySet()))
        assertFalse(memberRelayAllowed(relayOnly = true, self = guest, host = host, consented = emptySet()))
        assertTrue(memberRelayAllowed(relayOnly = true, self = guest, host = host, consented = setOf(host)))
        // Several publishers: nobody named, so nobody can be chosen.
        assertFalse(memberRelayAllowed(relayOnly = true, self = guest, host = null, consented = setOf(host)))
        // One's own relay, published from another device, needs no question.
        assertTrue(memberRelayAllowed(relayOnly = true, self = host, host = host, consented = emptySet()))
    }

    @Test fun `the choice is kept per person and host on this device`() {
        val storage = MemoryStorage()
        val store = SpryConsentStore(storage)
        assertEquals(emptySet(), store.hosts(guest))
        store.save(guest, host)
        store.save(guest, host)
        assertEquals(setOf(host), store.hosts(guest))
        assertEquals(emptySet(), store.hosts(other))
        // A new store over the same file reads the same choice: it survives a restart.
        assertEquals(setOf(host), SpryConsentStore(storage).hosts(guest))
        assertEquals(listOf("$guest:$host"), SpryShareStore.decode(storage.bytes))
        assertFailsWith<IllegalArgumentException> { store.save(guest, "not a key") }
        // An unreadable list is no choice at all.
        assertEquals(emptySet(), SpryConsentStore(MemoryStorage("{".toByteArray())).hosts(guest))
    }

    @Test fun `the question uses the web's words`() {
        assertEquals("Use Ada's relay?", MemberRelayWords.title("Ada"))
        assertEquals("Ada will see your address; others won't.", MemberRelayWords.message("Ada"))
        assertEquals("Use relay", MemberRelayWords.USE)
        assertEquals("Not now", MemberRelayWords.NOT_NOW)
    }
}
