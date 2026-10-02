package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.encodeJoinUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What a saved room keeps so it can follow its authority's rekeys. */
class SavedRoomEpochTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val base = "https://kithmoot.example/j/"
    private val secret = Entropy.bytes(32)
    private val who = PrimaryIdentity.create(deriveRoom(secret).roomId, now + 3600, now)
    private val host = createRoomInvitation()
    private val inviteUrl = encodeInvitationUrl(base, host.invitation, relays)

    @Test fun `a room saved from an invitation without its authority pins the link's inviter`() {
        val legacy = SavedRoom.create(secret, who, inviteUrl, relays, "The moot", now, null, authority = null)
        assertNull(legacy.authority)
        val pinned = legacy.withInvitationAuthority()
        assertEquals(host.invitation.canonicalInviter, pinned.authority)
        assertEquals(pinned.authority, SavedRoom.decode(pinned.json).authority)
    }

    @Test fun `an authority already recorded, or a legacy secret link, is left alone`() {
        val other = "ab".repeat(32)
        val recorded = SavedRoom.create(secret, who, inviteUrl, relays, "The moot", now, null, authority = other)
        assertSame(recorded, recorded.withInvitationAuthority())
        val legacyLink = SavedRoom.create(secret, who, encodeJoinUrl(base, secret, relays), relays, "Old", now, null, authority = null)
        assertNull(legacyLink.withInvitationAuthority().authority)
    }

    @Test fun `the epoch a room was said to be at is kept, highest first, and survives a rejoin`() {
        val room = SavedRoom.create(secret, who, inviteUrl, relays, "The moot", now, null, host.invitation.canonicalInviter)
        assertNull(room.epochHint)
        assertSame(room, room.withEpochHint(null))
        val told = room.withEpochHint(2)
        assertEquals(2, told.epochHint)
        assertSame(told, told.withEpochHint(1))
        assertEquals(3, told.withEpochHint(3).epochHint)
        assertEquals(2, SavedRoom.decode(told.json).epochHint)

        val rejoined = SavedRoom.create(secret, who, inviteUrl, relays, "The moot", now + 60, null, host.invitation.canonicalInviter)
            .retainingHistory(told)
        assertEquals(2, rejoined.epochHint)
    }
}
