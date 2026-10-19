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

    @Test fun `an opened invitation is a private conversation, an ordinary room, or refused`() = runTest {
        val room = Fixtures.room(37)
        val persistent = createRoomInvitation(persistent = true)
        val relays = listOf("wss://relay.example")
        fun link(policy: dev.forgesworn.kithmoot.protocol.RoomPolicy?, invitation: dev.forgesworn.kithmoot.protocol.RoomInvitation = persistent.invitation) =
            encodeInvitationUrl("https://kithmoot.forgesworn.dev/j/", invitation, relays, policy)
        suspend fun opened(link: String): OpenedInvitation? {
            val invite = sealInvite(link, bob.pubkey, room.roomId, alice)
            return classifyOpenedInvite(openInvite(invite, bob.pubkey, alice.pubkey, bob)!!, invite, bob.pubkey, alice.pubkey)
        }

        // The exact pair's room stays a private conversation.
        assertTrue(opened(link(dmPolicy(alice.pubkey, bob.pubkey))) is OpenedInvitation.PrivateConversation)

        // A room of the sender's with no member list, as the web client sends
        // from a roster row, is an ordinary room. This used to be refused.
        val group = opened(link(null))
        assertTrue(group is OpenedInvitation.Room)
        assertTrue(group!!.payload.invitation.persistent)

        // A room of two that is not this pair's is never an ordinary room.
        val carol = LocalSigner(Fixtures.key(88))
        val elsewhere = link(dmPolicy(alice.pubkey, carol.pubkey))
        assertNull(opened(elsewhere))
        assertEquals("This invitation is not for the claimed two-person conversation.", refusedInvitationReason(elsewhere))

        // A temporary room goes through the home screen's admission gate.
        val temporary = link(null, createRoomInvitation(persistent = false).invitation)
        assertNull(opened(temporary))
        assertTrue(refusedInvitationReason(temporary).contains("temporary room"))

        // Another device of the sender's is not an invitation to accept.
        val owner = Fixtures.primary(room, participantSeed = 1, deviceSeed = 2)
        val deviceKey = Fixtures.key(42)
        val pairing = encodeInvitationPairingLink(
            invitation = persistent.invitation,
            relays = relays,
            deviceSecretKey = deviceKey,
            credential = owner.enrolNow(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(deviceKey), room.roomId, Fixtures.CREDENTIAL_EXPIRY, 0),
        )
        assertNull(opened(pairing))

        // Somebody the invitation is not for gets nothing.
        val invite = sealInvite(link(null), bob.pubkey, room.roomId, alice)
        assertNull(classifyOpenedInvite(link(null), invite, carol.pubkey, alice.pubkey))
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
