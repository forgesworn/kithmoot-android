package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.HISTORY_WINDOW_SECONDS
import dev.forgesworn.kithmoot.protocol.MAX_HISTORY_EPOCHS
import dev.forgesworn.kithmoot.session.CHAT_RETENTION_SECONDS
import dev.forgesworn.kithmoot.session.MAX_PAST_EPOCHS
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A room reopened after a rekey rebuilds the epochs it left from the epoch
 * journal, so a message that lands late on one is still read
 * (kithmoot-android #128). The web client does the same on opening.
 */
class PastEpochsForTest {
    private val now = 1_800_000_000L
    private val roomSecret = ByteArray(32) { 7 }
    private fun secret(epoch: Int) = ByteArray(32) { (70 + epoch).toByte() }
    private fun rekey(at: Long) = NostrEvent(1, at, emptyList(), "", "a".repeat(64), "b".repeat(64), "c".repeat(128))

    private fun stored(current: Int, phase: EpochPhase = EpochPhase.ACTIVE) = StoredRoomEpoch(
        "d".repeat(64), "a".repeat(64), current, secret(current), emptyList(), phase, null, null, now,
    )

    private fun past(
        current: Int,
        secrets: Map<Int, ByteArray> = (1 until current).associateWith(::secret),
        rekeys: Map<Int, Long> = (1..current).associateWith { now - (current - it + 1) * 60L },
        phase: EpochPhase = EpochPhase.ACTIVE,
        left: Map<Int, Long> = emptyMap(),
    ) = pastEpochsFor(roomSecret, stored(current, phase), { secrets[it] }, { rekeys[it + 1]?.let(::rekey)?.createdAt ?: left[it] }, now)

    @Test fun `epochs left are rebuilt newest first, each left when the rekey out of it was made`() {
        val left = past(3)
        assertEquals(listOf(2, 1, 0), left.map { it.keys.epoch })
        assertEquals(listOf(now - 60, now - 120, now - 180), left.map { it.leftAt })
        assertEquals(deriveEpoch(RoomEpoch(2, secret(2))).id, left[0].keys.id)
        // Epoch 0 is the room's own secret, which the history never holds.
        assertEquals(deriveEpoch(RoomEpoch(0, roomSecret)).id, left[2].keys.id)
    }

    @Test fun `no more than sixteen, and none left longer ago than thirty days`() {
        assertEquals(16, MAX_PAST_EPOCHS)
        assertEquals(MAX_HISTORY_EPOCHS, MAX_PAST_EPOCHS)
        assertEquals(HISTORY_WINDOW_SECONDS, CHAT_RETENTION_SECONDS)
        assertEquals((19 downTo 4).toList(), past(20).map { it.keys.epoch })
        val stale = past(3, rekeys = mapOf(3 to now - 60, 2 to now - CHAT_RETENTION_SECONDS - 1, 1 to now - CHAT_RETENTION_SECONDS - 60))
        assertEquals(listOf(2), stale.map { it.keys.epoch })
        // Exactly thirty days ago is still inside the window; a second more is not.
        val edge = past(3, rekeys = mapOf(3 to now - HISTORY_WINDOW_SECONDS, 2 to now - HISTORY_WINDOW_SECONDS - 1, 1 to now - HISTORY_WINDOW_SECONDS - 2))
        assertEquals(listOf(2), edge.map { it.keys.epoch })
    }

    @Test fun `a weekly schedule keeps the last month and a skipped epoch does not shorten the sixteen`() {
        val weekly = past(10, rekeys = (1..10).associateWith { now - (10 - it + 1) * 7 * 86_400L })
        assertEquals(listOf(9, 8, 7, 6), weekly.map { it.keys.epoch })
        // An epoch with no secret is not one of the sixteen: the next older one takes its place.
        val gap = past(20, secrets = (1 until 20).filter { it != 15 }.associateWith(::secret))
        assertEquals((19 downTo 3).filter { it != 15 }, gap.map { it.keys.epoch })
    }

    @Test fun `an epoch an authority's grant handed over is read from the time the grant gave`() {
        // The rekey out of 2 was kept; none out of 1 or 0 was, but the grant's `passed` said when 1 was left.
        val handed = past(3, rekeys = mapOf(3 to now - 60), left = mapOf(1 to now - 86_400))
        assertEquals(listOf(2, 1), handed.map { it.keys.epoch })
        assertEquals(listOf(now - 60, now - 86_400), handed.map { it.leftAt })
    }

    @Test fun `an epoch with no kept secret or no kept rekey out of it is skipped, not guessed`() {
        assertEquals(listOf(2, 0), past(3, secrets = mapOf(2 to secret(2))).map { it.keys.epoch })
        assertEquals(listOf(1, 0), past(3, rekeys = mapOf(1 to now - 180, 2 to now - 120)).map { it.keys.epoch })
    }

    @Test fun `nothing for a room at epoch 0, without a journal, or that this device has left`() {
        assertEquals(emptyList(), past(0))
        assertEquals(emptyList(), pastEpochsFor(roomSecret, null, { secret(it) }, { now }, now))
        assertEquals(emptyList(), past(3, phase = EpochPhase.REMOVED))
        assertEquals(emptyList(), past(3, phase = EpochPhase.CLOSED))
    }
}
