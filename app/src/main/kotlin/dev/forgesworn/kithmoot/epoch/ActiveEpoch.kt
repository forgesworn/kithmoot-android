package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveRoom
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
