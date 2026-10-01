package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InvitationLookupTest {
    private val noCircle = { _: String -> false }
    private val host = createRoomInvitation(true)
    private val secret = ByteArray(32) { 12 }

    @Test fun `only a missing invitation widens the search`() = runTest {
        val missing = assertFailsWith<GroupInvitationException> { requestPersistentAdmission(host.invitation) { emptyList() } }
        assertTrue(isMissingInvitation(missing))

        val retirement = encodeInvitationRetirement(host.invitation, host.inviterSecretKey, 1_800_000_060)
        val retired = assertFailsWith<GroupInvitationException> { requestPersistentAdmission(host.invitation) { listOf(retirement) } }
        assertFalse(isMissingInvitation(retired))

        val one = encodePersistentInvitation(host, secret, 1_800_000_000)
        val other = encodePersistentInvitation(host, ByteArray(32) { 13 }, 1_800_000_001)
        val conflicting = assertFailsWith<GroupInvitationException> { requestPersistentAdmission(host.invitation) { listOf(one, other) } }
        assertFalse(isMissingInvitation(conflicting))

        assertFalse(isMissingInvitation(IllegalStateException("boom")))
    }

    @Test fun `asks this device's relays, then the defaults, never the link's again`() {
        assertEquals(
            listOf("wss://relay.damus.io", "wss://nostr.mom"),
            widerInvitationRelays(
                listOf("wss://nos.lol", "wss://relay.primal.net/"),
                listOf("wss://relay.damus.io", "wss://NOS.LOL"),
                listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.damus.io"),
                noCircle,
            ),
        )
    }

    @Test fun `a sheltered link never goes looking on public relays`() {
        val circle = { url: String -> url == "wss://box.example" }
        assertEquals(emptyList(), widerInvitationRelays(listOf("wss://box.example"), listOf("wss://relay.damus.io"), listOf("wss://nos.lol"), circle))
        assertEquals(emptyList(), widerInvitationRelays(listOf("wss://nos.lol", "wss://box.example"), listOf("wss://relay.damus.io"), listOf("wss://nostr.mom"), circle))
    }

    @Test fun `a circle relay of this device is not asked about somebody else's link`() {
        val circle = { url: String -> url == "wss://box.example" }
        assertEquals(listOf("wss://nostr.mom"), widerInvitationRelays(listOf("wss://nos.lol"), listOf("wss://box.example"), listOf("wss://nostr.mom"), circle))
    }

    @Test fun `only websocket relays are asked`() {
        assertEquals(listOf("wss://nostr.mom"), widerInvitationRelays(listOf("wss://nos.lol"), listOf("https://example.com", ""), listOf("wss://nostr.mom"), noCircle))
    }

    @Test fun `a re-signed invitation also reaches the relays only the link names`() {
        assertEquals(
            listOf("wss://relay.primal.net"),
            linkOnlyRelays(listOf("wss://relay.damus.io", "wss://nos.lol"), listOf("wss://nos.lol/", "wss://relay.primal.net"), noCircle),
        )
        assertEquals(emptyList(), linkOnlyRelays(listOf("wss://nos.lol"), listOf("wss://box.example"), { it == "wss://box.example" }))
    }
}
