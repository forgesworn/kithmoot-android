package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.epoch.StoredRoomEpoch
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import dev.forgesworn.kithmoot.relay.Filter
import java.util.TreeMap

/**
 * The room's rekeys (kind 1462) from its authority, as an open room asks for them
 * (`RoomSession.rekeyFilter`): tagged with the stable room id, which never moves, so a
 * listener that has fallen behind still sees every later one.
 */
fun backgroundRekeyFilter(stableRoomId: String, authority: String) = Filter(
    kinds = listOf(KIND_ROOM_REKEY),
    authors = listOf(authority),
    // "#d", not "d": Filter's wire form for a tag filter (see relay/Filter.kt).
    tags = mapOf("#d" to listOf(stableRoomId)),
)

/**
 * Follows a saved room's rekeys while nobody has it open, so its notifications and call rings
 * survive a rekey (kithmoot phase 2a, finding 1). Without this the background listener reads
 * only under the epoch the journal holds, and goes quiet at the first rekey until somebody
 * opens the room; a scheduled weekly rekey would make that every room nobody opens in a week.
 *
 * Each rekey is opened with this device's own copy, sealed to the saved device key (Android
 * holds no seal keys), under the key of the epoch it leaves, and committed to [vault] through
 * [EpochVault.follow]: the transition and activation an open room makes, as one step, and a
 * no-op if an open room got there first. The caller then moves its subscriptions to the new
 * epoch ([offer] says when). Every verified rekey is also kept as history, as an open room keeps it, so the epoch
 * just left still has a time it was left and its late messages are still read.
 *
 * It follows only what an open room would follow without asking anyone: a rekey into the next
 * epoch that carries this device a secret. A removal of this participant, a close, a rekey
 * that skips this device and a gap are all left for the open room, exactly as before: a
 * removed device finds no copy, and the background listener stops at the epoch it holds.
 * [mayFollow] is asked just before each commit; the service says no while the room is open in
 * the app, and for a room with a Bothy schedule, whose cadence only an open room can retire.
 *
 * Not thread-safe; one per watched room, fed from that room's subscription.
 */
class BackgroundRekeyFollower(
    private val vault: EpochVault,
    private val stableRoomId: String,
    private val authority: String,
    private val participant: String,
    private val deviceSecretKey: () -> ByteArray,
    private val mayFollow: () -> Boolean,
    private val now: () -> Long,
) {
    /** The first authority-signed rekey seen for each epoch above the journal's: the one followed. */
    private val pending = TreeMap<Int, NostrEvent>()

    /**
     * Take one event from the subscription. True when the journal's epoch is no longer the one
     * the listener was reading under, whoever moved it: its subscriptions should move too.
     */
    fun offer(event: NostrEvent): Boolean {
        val epoch = peekRekeyEpoch(event, stableRoomId, authority) ?: return false
        val before = journal()?.currentEpoch ?: return false
        // Advisory, as in an open room: what cannot be kept is simply not read later.
        runCatching { vault.remember(stableRoomId, emptyList(), listOf(event)) }
        pending.putIfAbsent(epoch, event)
        drain()
        return journal()?.currentEpoch?.let { it != before } ?: false
    }

    private fun journal(): StoredRoomEpoch? = runCatching { vault.get(stableRoomId) }.getOrNull()

    private fun drain() {
        while (true) {
            val stored = journal() ?: return
            if (stored.phase != EpochPhase.ACTIVE) return
            pending.headMap(stored.currentEpoch, true).clear()
            val next = pending[stored.currentEpoch + 1] ?: return
            val current = deriveEpoch(RoomEpoch(stored.currentEpoch, stored.currentSecret))
            val key = deviceSecretKey()
            val notice = try {
                decodeRekeyEvent(next, stableRoomId, authority, current, key)
            } finally { key.fill(0) }
            if (notice == null || notice.secret == null || notice.closed ||
                notice.removed.any { it.equals(participant, ignoreCase = true) }) return
            if (!mayFollow()) return
            val followed = runCatching { vault.follow(stableRoomId, notice, next.id, now()) }.getOrNull()
            notice.secret?.fill(0)
            // Not followed and nobody else moved the journal either: stop rather than spin.
            if (followed == null && journal()?.currentEpoch == stored.currentEpoch) return
        }
    }
}
