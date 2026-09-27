package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.SecondaryIdentity
import dev.forgesworn.kithmoot.session.enrolNow
import kotlin.test.*

/** `ended` and `canShareInvite` on the summary: what the home rows list
 *  reads without the saved capabilities themselves (design-home-rooms.md
 *  section 3, "Ended"). */
class SavedRoomSummaryTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val base = "https://kithmoot.example/j/"

    private fun room(): SavedRoom {
        val secret = Entropy.bytes(32)
        val derived = deriveRoom(secret)
        val who = PrimaryIdentity.create(derived.roomId, now + 3600, now)
        val host = createRoomInvitation()
        return SavedRoom.create(secret, who, encodeInvitationUrl(base, host.invitation, relays), relays,
            "Workshop", now, host, host.invitation.canonicalInviter)
    }

    @Test fun `neither retired nor moved on is not ended`() {
        assertFalse(room().summary().ended)
    }

    @Test fun `a retired invitation is ended`() {
        assertTrue(room().invitationRetired().summary().ended)
    }

    @Test fun `keys moved on is ended`() {
        assertTrue(room().keysChanged().summary().ended)
    }

    @Test fun `an ordinary room can share its invite link`() {
        assertTrue(room().summary().canShareInvite)
    }

    @Test fun `an ended room cannot share its invite link`() {
        assertFalse(room().invitationRetired().summary().canShareInvite)
    }

    @Test fun `a paired secondary device cannot share its invite link`() {
        val original = room()
        val owner = original.identity(now) as PrimaryIdentity
        val deviceKey = Entropy.bytes(32)
        val credential = owner.enrolNow(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(deviceKey), original.id, now + 60, now)
        val secondary = SecondaryIdentity.adopt(credential, deviceKey, original.id, now)!!
        val saved = SavedRoom.create(original.secret, secondary, original.joinUrl, relays, "Paired", now, null, original.authority)
        assertFalse(saved.summary().canShareInvite)
    }
}
