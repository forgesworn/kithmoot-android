package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateConversationTest {
    private val alice = LocalSigner(Fixtures.key(41))
    private val bob = LocalSigner(Fixtures.key(73))

    @Test fun `external signers seal and validate the exact private room`() = runTest {
        val room = Fixtures.room(19)
        val host = createRoomInvitation(persistent = true)
        val policy = dmPolicy(alice.pubkey, bob.pubkey)
        val link = encodeInvitationUrl(
            "https://kithmoot.forgesworn.dev/j/",
            host.invitation,
            listOf("wss://relay.example"),
            policy,
        )

        val invite = sealInvite(link, bob.pubkey, room.roomId, alice)
        val opened = openInvite(invite, bob.pubkey, alice.pubkey, bob)
        assertNotNull(opened)
        assertEquals(policy, decodePrivateConversationInvite(opened!!, invite, bob.pubkey, alice.pubkey)?.policy)
        assertEquals(link, openInvite(invite, alice.pubkey, alice.pubkey, alice))
        assertNull(openInvite(invite, Fixtures.primary(room, 99, 101).participant, alice.pubkey, bob))

        val broad = encodeInvitationUrl(
            "https://kithmoot.forgesworn.dev/j/",
            host.invitation,
            listOf("wss://relay.example"),
            policy.copy(members = null),
        )
        assertNull(decodePrivateConversationInvite(broad, invite, bob.pubkey, alice.pubkey))
    }

    @Test fun `session exposes an invitation only after confirmed publication`() = runTest {
        val introduction = Fixtures.room(23)
        val aliceIdentity = Fixtures.primary(introduction, 41, 42)
        val relay = FakeRelay()
        val session = session(introduction, aliceIdentity, relay)
        session.join()
        advanceUntilIdle()
        val invite = ChatInvite(bob.pubkey, Fixtures.room(29).roomId, "sealed")

        assertTrue(session.sendInviteConfirmed(invite))
        assertEquals(invite, session.chat.value.single { it.invite != null }.invite)

        relay.confirmsPublications = false
        val refused = ChatInvite(bob.pubkey, Fixtures.room(31).roomId, "another-sealed-link")
        assertFalse(session.sendInviteConfirmed(refused))
        assertNull(session.chat.value.firstOrNull { it.invite == refused })
    }
}
