package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.epoch.pastEpochsFor
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.storage.MemoryStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The background listener follows a room's rekeys while nobody has it open, so its
 * notifications and call rings survive a rekey (kithmoot phase 2a, finding 1), and leaves
 * everything an open room would have to ask about to the open room.
 */
class BackgroundRekeyTest {
    private val now = 1_800_000_000L
    private val roomSecret = ByteArray(32) { 7 }
    private val room = deriveRoom(roomSecret).roomId
    private val authoritySecret = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private val deviceSecret = Fixtures.key(42)
    private val device = Schnorr.publicKeyHex(deviceSecret)
    private val participant = "51".repeat(32)
    private val other = Schnorr.publicKeyHex(Fixtures.key(43))
    private val epochs = (0..3).map { if (it == 0) RoomEpoch(0, roomSecret) else RoomEpoch(it, ByteArray(32) { b -> (60 + it + b % 3).toByte() }) }

    private val vault = EpochVault(MemoryStorage(), MemoryStorage()).also { it.initialise(room, authority, roomSecret, now - 1_000) }
    private var open = false

    private fun follower() = BackgroundRekeyFollower(
        vault, room, authority, participant,
        deviceSecretKey = { deviceSecret.copyOf() },
        mayFollow = { !open },
        now = { now },
    )

    private fun rekey(
        into: Int,
        sealedTo: List<String> = listOf(device, other),
        removed: List<String> = emptyList(),
        closed: Boolean = false,
        scheduled: Boolean = removed.isEmpty() && !closed,
        signer: ByteArray = authoritySecret,
    ): NostrEvent = encodeRekeyEvent(
        room, signer, deriveEpoch(epochs[into - 1]), epochs[into], sealedTo, removed, now - 100L * (4 - into),
        closed = closed, commit = true, scheduled = scheduled,
    )

    private fun stored() = vault.get(room)!!

    @Test fun `asks the relays for the room's rekeys from its authority, under the stable room id`() {
        val filter = backgroundRekeyFilter(room, authority)
        assertEquals(listOf(KIND_ROOM_REKEY), filter.kinds)
        assertEquals(listOf(authority), filter.authors)
        assertEquals(mapOf("#d" to listOf(room)), filter.tags)
    }

    @Test fun `a scheduled rekey is followed with the device key, and the epoch left is still read`() {
        val event = rekey(1)
        assertTrue(follower().offer(event))

        assertEquals(EpochPhase.ACTIVE, stored().phase)
        assertEquals(1, stored().currentEpoch)
        assertContentEquals(epochs[1].secret, stored().currentSecret)
        // What the listener rebuilds its subscriptions from (`activeEpochFor`): chat and bells
        // under the new epoch...
        assertEquals(deriveEpoch(epochs[1]).id, deriveEpoch(RoomEpoch(stored().currentEpoch, stored().currentSecret)).id)
        // ...and the epoch just left, with when it was left, for a message that lands late on it.
        val past = pastEpochsFor(roomSecret, stored(), { vault.secretAt(room, it) }, { vault.leftAt(room, it) }, now)
        assertEquals(listOf(0), past.map { it.keys.epoch })
        assertEquals(event.createdAt, past.single().leftAt)
    }

    @Test fun `rekeys arriving out of order are followed in order`() {
        val follower = follower()
        assertFalse(follower.offer(rekey(2)))
        assertEquals(0, stored().currentEpoch)
        assertTrue(follower.offer(rekey(1)))
        assertEquals(2, stored().currentEpoch)
        assertContentEquals(epochs[2].secret, stored().currentSecret)
        assertFalse(follower.offer(rekey(1)), "a replayed rekey changes nothing")
    }

    @Test fun `somebody else's removal is followed like any rekey that carries this device a copy`() {
        assertTrue(follower().offer(rekey(1, removed = listOf("66".repeat(32)))))
        assertEquals(1, stored().currentEpoch)
        assertEquals(listOf("66".repeat(32)), stored().removed)
    }

    @Test fun `a removed device finds no copy and stays where it was, for the open room to say`() {
        assertFalse(follower().offer(rekey(1, sealedTo = listOf(other), removed = listOf(participant))))
        assertEquals(EpochPhase.ACTIVE, stored().phase)
        assertEquals(0, stored().currentEpoch)
    }

    @Test fun `a close, or a rekey that leaves this device out, is left for the open room`() {
        assertFalse(follower().offer(rekey(1, closed = true)))
        assertFalse(follower().offer(rekey(1, sealedTo = listOf(other))))
        assertEquals(EpochPhase.ACTIVE, stored().phase)
        assertEquals(0, stored().currentEpoch)
    }

    @Test fun `a rekey not signed by the authority is ignored`() {
        assertFalse(follower().offer(rekey(1, signer = Fixtures.key(44))))
        assertEquals(0, stored().currentEpoch)
    }

    @Test fun `nothing is followed while the room is open, and the replay after it closes is`() {
        val follower = follower()
        open = true
        assertFalse(follower.offer(rekey(1)))
        assertEquals(0, stored().currentEpoch)
        open = false
        assertTrue(follower.offer(rekey(1)))
        assertEquals(1, stored().currentEpoch)
    }

    @Test fun `an epoch the open room already committed is not committed again`() {
        val notice = RekeyNotice(1, emptyList(), null, false, epochs[1].secret, now - 300, scheduled = true)
        vault.beginTransition(room, 0, notice, "aa".repeat(32), null, now)
        vault.activate(room, 1, now)
        val before = stored()

        assertFalse(follower().offer(rekey(1)))
        assertEquals(1, stored().currentEpoch)
        assertEquals(before.updatedAt, stored().updatedAt)
    }

    @Test fun `the open room committing an epoch the background already followed does not fail`() {
        val event = rekey(1)
        assertTrue(follower().offer(event))
        // The open room read the journal at epoch 0 before the background committed, and now
        // commits the same rekey: the very path `RoomViewModel.commitRoomEpoch` takes.
        val notice = RekeyNotice(1, emptyList(), null, false, epochs[1].secret, event.createdAt, scheduled = true)
        val begun = vault.beginTransition(room, 0, notice, event.id, null, now)
        assertEquals(EpochPhase.ACTIVE, begun.phase)
        assertEquals(1, vault.activate(room, 1, now).currentEpoch)
        assertContentEquals(epochs[1].secret, stored().currentSecret)
    }
}
