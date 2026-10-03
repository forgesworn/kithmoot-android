package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.session.CHAT_RETENTION_SECONDS
import dev.forgesworn.kithmoot.session.MAX_PAST_EPOCHS
import dev.forgesworn.kithmoot.session.PastEpoch
import dev.forgesworn.kithmoot.storage.SavedRoom

/**
 * The traffic key and id a saved room is on right now, following any rekey
 * recorded in its epoch journal entry [stored] - the one derivation
 * `RoomViewModel` and the background call bell listener must both use, so
 * neither can drift onto a stale (pre-rekey) key. Before this was extracted,
 * the bell listener derived `deriveRoom(currentSecret).roomKey` directly and
 * so rang under the epoch-0 key forever, even after the room had moved to
 * epoch 1 or beyond.
 *
 * [stored] is null for a room with no `authority` (never rekeyed, or not
 * yet initialised in the epoch journal); epoch 0 then derives directly from
 * the saved room secret, exactly as before any epoch journal existed.
 *
 * Returns null when the room's epoch phase is REMOVED or CLOSED: there is
 * no current key to watch or derive, and a background listener must stop
 * treating this room as live. A caller that must react to this while a
 * person is actively opening the room raises its own message instead of
 * calling this helper directly - see `RoomViewModel.activeRoomEpoch`.
 */
fun activeEpochFor(saved: SavedRoom, stored: StoredRoomEpoch?): EpochKeys? {
    if (stored == null) {
        val room = deriveRoom(saved.secret)
        return EpochKeys(0, room.roomId, room.roomKey)
    }
    if (stored.phase == EpochPhase.REMOVED || stored.phase == EpochPhase.CLOSED) return null
    return deriveEpoch(RoomEpoch(stored.currentEpoch, stored.currentSecret))
}

/**
 * The epochs a room has left that this device can still read chat on, rebuilt
 * from what the epoch journal kept: newest first, at most [MAX_PAST_EPOCHS],
 * none left longer ago than chat is kept.
 *
 * An epoch counts as left when the authority rekeyed out of it, so its
 * `leftAt` is the `createdAt` of [rekeyAt] the next epoch. An epoch with no
 * kept secret, or no kept rekey out of it, is skipped rather than guessed at.
 * Epoch 0's secret is the room's own, [roomSecret]; later ones come from
 * [secretAt] (`EpochVault.secretAt`).
 *
 * Empty for a room with no journal entry, one still at epoch 0, and one the
 * device was removed from or that was closed.
 */
fun pastEpochsFor(
    roomSecret: ByteArray,
    stored: StoredRoomEpoch?,
    secretAt: (Int) -> ByteArray?,
    rekeyAt: (Int) -> NostrEvent?,
    now: Long,
): List<PastEpoch> {
    if (stored == null || stored.phase == EpochPhase.REMOVED || stored.phase == EpochPhase.CLOSED) return emptyList()
    val oldest = now - CHAT_RETENTION_SECONDS
    return (stored.currentEpoch - 1 downTo maxOf(0, stored.currentEpoch - MAX_PAST_EPOCHS)).mapNotNull { epoch ->
        val secret = (if (epoch == 0) roomSecret else secretAt(epoch)) ?: return@mapNotNull null
        val leftAt = rekeyAt(epoch + 1)?.createdAt ?: return@mapNotNull null
        if (leftAt < oldest) return@mapNotNull null
        PastEpoch(deriveEpoch(RoomEpoch(epoch, secret)), leftAt)
    }
}
