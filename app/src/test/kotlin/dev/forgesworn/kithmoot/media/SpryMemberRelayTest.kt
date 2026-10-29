package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.SpryDescriptor
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.protocol.standard
import dev.forgesworn.kithmoot.ui.MemberRelayWords
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    private val token = "a".repeat(43)
    private fun offer(node: dev.forgesworn.kithmoot.protocol.NostrEvent? = card("{\"profile\":\"spry-standard-v1\",\"v\":1}"), at: String = endpoint,
        relayed: Boolean = false, tokenValue: String = token) =
        SpryOffer(at, tokenValue, now + 86_400, node, relayed)
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

    @Test fun `any offer but this device's own paired node is another member's`() {
        assertTrue(spryMemberOffer(offer(), null))
        assertTrue(spryMemberOffer(offer(null, "https://relay.example"), null))
        assertTrue(spryMemberOffer(offer(null, "https://relay.example"), ownEndpoint))
        assertFalse(spryMemberOffer(offer(null, "https://relay.example"), "https://relay.example"))
        assertFalse(spryMemberOffer(offer(at = ownEndpoint), ownEndpoint))
    }

    @Test fun `another member's https relay waits for the same choice, own and settings relays never ask`() {
        val https = offer(null, "https://relay.example")
        // Not allowed: never redeemed, the same gate as a built-in relay.
        assertFalse(spryOfferUsable(https, null) { false })
        assertFalse(spryOfferUsable(https, "https://other.example") { false })
        assertTrue(spryOfferUsable(https, null) { true })
        // It needs no standard card, which only a spry: offer carries.
        assertFalse(https.standard)
        // This device's own paired https node is never asked about.
        assertTrue(spryOfferUsable(https, "https://relay.example") { false })
        // The gate reads Hide my IP address and the host's choice, as for spry:.
        assertTrue(memberRelayAllowed(relayOnly = false, self = guest, host = host, consented = emptySet()))
        assertFalse(memberRelayAllowed(relayOnly = true, self = guest, host = host, consented = emptySet()))
        assertTrue(memberRelayAllowed(relayOnly = true, self = guest, host = host, consented = setOf(host)))
        assertTrue(memberRelayAllowed(relayOnly = true, self = host, host = host, consented = emptySet()))
    }

    @Test fun `one choice covers a host's relays whatever the transport`() {
        val consented = setOf(host)
        val builtIn = offer()
        val https = offer(null, "https://relay.example")
        val allowed = { memberRelayAllowed(relayOnly = true, self = guest, host = host, consented = consented) }
        assertTrue(spryOfferUsable(builtIn, null, allowed))
        assertTrue(spryOfferUsable(https, null, allowed))
        // A different host is still asked.
        val asked = { memberRelayAllowed(relayOnly = true, self = guest, host = other, consented = consented) }
        assertFalse(spryOfferUsable(https, null, asked))
        assertFalse(spryOfferUsable(builtIn, null, asked))
    }

    @Test fun `an https relay is attributed to its one unmarked host, and unattributed to several`() {
        val https = offer(null, "https://relay.example")
        val at = "https://relay.example"
        assertEquals(host, spryOfferHost(listOf(descriptor(host, https)), at, token))
        assertEquals(host, spryOfferHost(listOf(descriptor(host, https, "d1"), descriptor(host, https, "d2")), at, token))
        // An old client's unmarked copy beside the host's offer: two unmarked publishers, nobody named.
        assertNull(spryOfferHost(listOf(descriptor(host, https), descriptor(other, https)), at, token))
        assertFalse(memberRelayAllowed(relayOnly = true, self = guest, host = null, consented = setOf(host)))
    }

    @Test fun `a member's relay is usable only from a standard node and only once allowed`() {
        assertTrue(spryOfferUsable(offer(), null) { true })
        assertFalse(spryOfferUsable(offer(), null) { false })
        // A private mesh node is never contacted from another device, allowed or not.
        assertFalse(spryOfferUsable(offer(card("{\"address\":\"[fd12:3456::2]:3745\",\"profile\":\"spry-peer-v1\",\"v\":1}")), null) { true })
        // This device's own node needs no choice; another member's https offer does (below).
        assertTrue(spryOfferUsable(offer(null, ownEndpoint), ownEndpoint) { false })
    }

    @Test fun `the host is named only when exactly one participant publishes the relay unmarked`() {
        assertEquals(host, spryOfferHost(listOf(descriptor(host, offer())), endpoint, token))
        // Two devices of one person still name one host.
        assertEquals(host, spryOfferHost(listOf(descriptor(host, offer(), "d1"), descriptor(host, offer(), "d2")), endpoint, token))
        assertNull(spryOfferHost(listOf(descriptor(host, offer()), descriptor(other, offer())), endpoint, token))
        assertNull(spryOfferHost(listOf(descriptor(host, offer(null, "https://relay.example"))), endpoint, token))
        assertNull(spryOfferHost(emptyList(), endpoint, token))
    }

    @Test fun `host naming ignores marked copies and needs the same token`() {
        val copy = offer(relayed = true)
        // A copy beside the host's own offer changes nothing.
        assertEquals(host, spryOfferHost(listOf(descriptor(host, offer()), descriptor(other, copy)), endpoint, token))
        assertEquals(host, spryOfferHost(listOf(descriptor(other, copy), descriptor(host, offer())), endpoint, token))
        // A copy alone names nobody.
        assertNull(spryOfferHost(listOf(descriptor(other, copy)), endpoint, token))
        assertNull(spryOfferHost(listOf(descriptor(other, copy), descriptor(guest, copy)), endpoint, token))
        // The same endpoint under another token is another grant: it does not name this host.
        val renewed = "z".repeat(43)
        assertNull(spryOfferHost(listOf(descriptor(host, offer(tokenValue = renewed))), endpoint, token))
        assertEquals(host, spryOfferHost(listOf(descriptor(host, offer(tokenValue = renewed)), descriptor(other, offer())), endpoint, renewed))
    }

    @Test fun `an old client's unmarked copy and the host's offer name nobody, so a Hide my IP guest fails closed and others still work`() {
        val named = spryOfferHost(listOf(descriptor(host, offer()), descriptor(other, offer())), endpoint, token)
        assertNull(named)
        assertFalse(memberRelayAllowed(relayOnly = true, self = guest, host = named, consented = setOf(host, other)))
        assertTrue(memberRelayAllowed(relayOnly = false, self = guest, host = named, consented = emptySet()))
    }

    @Test fun `a marked copy alone fails closed with Hide my IP and works without it`() {
        val copy = offer(relayed = true)
        val named = spryOfferHost(listOf(descriptor(other, copy)), endpoint, token)
        assertNull(named)
        // Hide my IP on: not allowed whoever was chosen, so it is never redeemed.
        assertFalse(spryOfferUsable(copy, null) { memberRelayAllowed(relayOnly = true, self = guest, host = named, consented = setOf(host, other)) })
        val https = offer(null, "https://relay.example", relayed = true)
        assertFalse(spryOfferUsable(https, null) { memberRelayAllowed(relayOnly = true, self = guest, host = null, consented = setOf(host)) })
        // Hide my IP off: the copy is used automatically, no question.
        assertTrue(spryOfferUsable(copy, null) { memberRelayAllowed(relayOnly = false, self = guest, host = named, consented = emptySet()) })
        assertTrue(spryOfferUsable(https, null) { memberRelayAllowed(relayOnly = false, self = guest, host = null, consented = emptySet()) })
    }

    @Test fun `a guest republishes another member's offer only as a marked copy it may use`() {
        val https = offer(null, "https://relay.example")
        val builtIn = offer()
        for (member in listOf(https, builtIn)) {
            val copy = spryRepublication(member, ownEndpoint, usable = true)
            assertEquals(member.copy(relayed = true), copy)
            assertTrue(copy!!.relayed)
        }
        // A guest who said Not now does not copy the offer.
        assertNull(spryRepublication(https, ownEndpoint, usable = false))
        assertNull(spryRepublication(builtIn, null, usable = false))
        // An own offer is published as it is, never marked, even from a marked store.
        val own = offer(null, ownEndpoint)
        assertEquals(own, spryRepublication(own, ownEndpoint, usable = true))
        assertFalse(spryRepublication(own.copy(relayed = true), ownEndpoint, usable = true)!!.relayed)
    }

    @Test fun `the relayed mark is the boolean true on the wire and nothing else`() {
        val base = offer(null, "https://relay.example")
        fun json(relayed: JsonElement?) = buildJsonObject {
            for ((key, value) in base.toJson()) put(key, value)
            if (relayed != null) put("relayed", relayed)
        }
        val parsed = { value: JsonElement? -> SpryOffer.parse(json(value), now)!!.relayed }
        assertTrue(parsed(JsonPrimitive(true)))
        for (wrong in listOf(JsonPrimitive(false), JsonPrimitive("true"),
            JsonPrimitive(1), JsonNull, JsonObject(emptyMap()),
            JsonArray(emptyList()))) assertFalse(parsed(wrong))
        assertFalse(parsed(null))
        // Emitted only as true, never false.
        assertFalse(base.toJson().containsKey("relayed"))
        assertEquals(JsonPrimitive(true), base.copy(relayed = true).toJson()["relayed"])
        assertTrue(SpryOffer.parse(base.copy(relayed = true).toJson(), now)!!.relayed)
        assertFalse(SpryOffer.parse(base.toJson(), now)!!.relayed)
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
