package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `activeEpochFor` is the fix for finding 4: the background call bell
 * listener used to derive `deriveRoom(currentSecret).roomKey` directly and
 * so rang forever under the epoch-0 key, even once a room had rekeyed past
 * it. Both `RoomViewModel.activeRoomEpoch` and the bell listener's
 * `watchFor` now call this one function, so neither can drift from the
 * other.
 */
class ActiveEpochTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")

    private fun room(): SavedRoom {
        val secret = Entropy.bytes(32)
        val derived = deriveRoom(secret)
        val who = PrimaryIdentity.create(derived.roomId, now + 3600, now)
        val host = createRoomInvitation()
        return SavedRoom.create(
            secret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays),
            relays, "Workshop", now, host, host.invitation.canonicalInviter,
        )
    }

    @Test
    fun `with no journal entry, epoch 0 derives straight from the room secret`() {
        val saved = room()
        val epoch = activeEpochFor(saved, null)
        val expected = deriveRoom(saved.secret)
        assertEquals(0, epoch?.epoch)
        assertEquals(expected.roomId, epoch?.id)
        assertContentEquals(expected.roomKey, epoch?.key)
    }

    @Test
    fun `an active room at epoch 2 matches deriveEpoch exactly`() {
        val saved = room()
        val secret = Entropy.bytes(32)
        val stored = StoredRoomEpoch(saved.id, saved.authority!!, 2, secret, emptyList(), EpochPhase.ACTIVE, null, null, now)

        val epoch = activeEpochFor(saved, stored)
        val expected = deriveEpoch(RoomEpoch(2, secret))
        assertEquals(expected.epoch, epoch?.epoch)
        assertEquals(expected.id, epoch?.id)
        assertContentEquals(expected.key, epoch?.key)
    }

    @Test
    fun `a room pending cadence retirement still watches on its current epoch`() {
        val saved = room()
        val secret = Entropy.bytes(32)
        val stored = StoredRoomEpoch(
            saved.id, saved.authority!!, 1, secret, emptyList(),
            EpochPhase.PENDING_CADENCE_RETIREMENT, null, null, now,
        )

        val epoch = activeEpochFor(saved, stored)
        assertEquals(deriveEpoch(RoomEpoch(1, secret)).key.toList(), epoch?.key?.toList())
    }

    @Test
    fun `REMOVED and CLOSED rooms have no current epoch to watch`() {
        val saved = room()
        val secret = Entropy.bytes(32)
        val removed = StoredRoomEpoch(saved.id, saved.authority!!, 1, secret, emptyList(), EpochPhase.REMOVED, null, "cause", now)
        val closed = StoredRoomEpoch(saved.id, saved.authority!!, 1, secret, emptyList(), EpochPhase.CLOSED, null, "cause", now)

        assertNull(activeEpochFor(saved, removed))
        assertNull(activeEpochFor(saved, closed))
    }
}
