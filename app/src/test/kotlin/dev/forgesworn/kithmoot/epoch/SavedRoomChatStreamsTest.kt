package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.ROOM_CHAT_CHANNELS
import dev.forgesworn.kithmoot.session.deriveChatChannel
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat streams a self-destruct reads to take back a device's own envelope 2 messages: epoch 0,
 * the epoch the journal says the room is on, and each earlier epoch whose secret is still kept,
 * each under the main chat, `control` and `assignments`.
 */
class SavedRoomChatStreamsTest {
    private val now = 1_800_000_000L
    private val relays = listOf("wss://relay.example")
    private val saved: SavedRoom = Entropy.bytes(32).let { secret ->
        val derived = deriveRoom(secret)
        val who = PrimaryIdentity.create(derived.roomId, now + 3600, now)
        val host = createRoomInvitation()
        SavedRoom.create(secret, who, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, relays),
            relays, "Workshop", now, host, host.invitation.canonicalInviter)
    }
    private val secrets = (1..4).associateWith { ByteArray(32) { i -> (it * 10 + i).toByte() } }

    private fun stored(epoch: Int, phase: EpochPhase = EpochPhase.ACTIVE) =
        StoredRoomEpoch(saved.id, saved.authority!!, epoch, secrets[epoch] ?: ByteArray(32) { 7 }, emptyList(), phase, null, if (phase == EpochPhase.ACTIVE) null else "cause", now)

    private fun expected(vararg epochs: Int): Set<String> {
        val roots = epochs.map { if (it == 0) deriveRoom(saved.secret).let { r -> r.roomId to r.roomKey } else deriveEpoch(RoomEpoch(it, secrets.getValue(it))).let { e -> e.id to e.key } }
        return roots.flatMap { (id, key) -> ROOM_CHAT_CHANNELS.map { deriveChatChannel(id, key, it).id } }.toSet()
    }

    @Test fun `a room with no journal entry has epoch 0's three streams`() {
        val streams = savedRoomChatStreams(saved, null) { null }
        assertEquals(3, streams.size)
        assertEquals(expected(0), streams.map { it.d }.toSet())
        val main = streams.single { it.d == saved.id }
        assertContentEquals(deriveRoom(saved.secret).roomKey, main.key)
    }

    @Test fun `a room on epoch 3 has epoch 0, 3 and each earlier epoch whose secret is kept`() {
        val streams = savedRoomChatStreams(saved, stored(3)) { if (it == 2) secrets.getValue(2).copyOf() else null }
        assertEquals(expected(0, 3, 2), streams.map { it.d }.toSet())
        assertEquals(9, streams.size)
        // The secrets it was handed are its own to wipe, and the journal's stay as they were.
        assertContentEquals(secrets.getValue(3), stored(3).currentSecret)
    }

    @Test fun `a closed or removed room keeps the epoch it was on, whatever its history says`() {
        for (phase in listOf(EpochPhase.CLOSED, EpochPhase.REMOVED)) {
            val streams = savedRoomChatStreams(saved, stored(2, phase)) { null }
            assertEquals(expected(0, 2), streams.map { it.d }.toSet(), "$phase")
        }
    }

    @Test fun `a room still on epoch 0 adds nothing to epoch 0`() {
        val streams = savedRoomChatStreams(saved, stored(0)) { error("epoch 0 is the room's own secret") }
        assertEquals(expected(0), streams.map { it.d }.toSet())
    }

    @Test fun `only as far back as the journal keeps anything is looked at`() {
        val asked = mutableListOf<Int>()
        savedRoomChatStreams(saved, StoredRoomEpoch(saved.id, saved.authority!!, 40, secrets.getValue(4), emptyList(), EpochPhase.ACTIVE, null, null, now)) { asked += it; null }
        assertEquals((39 downTo 40 - dev.forgesworn.kithmoot.protocol.MAX_MEMBER_EPOCH_CHAIN).toList(), asked)
        assertTrue(asked.all { it >= 1 })
    }
}
