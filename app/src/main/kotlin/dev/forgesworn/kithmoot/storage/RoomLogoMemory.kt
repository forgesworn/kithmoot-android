package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.protocol.RoomLogoRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-local artwork for temporary rooms and immediate updates across chat/call views. */
class RoomLogoMemory {
    private data class Entry(val scope: String, val logo: SavedRoomLogo, val ends: Long?)
    private val held = linkedMapOf<String, Entry>()
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    private fun scope(room: SavedRoom) = "${room.participant}:${room.devicePubkey}"
    private fun eligible(room: SavedRoom, now: Long) = !room.anonymous && !room.retired && !room.movedOn &&
        !room.ended(now) && room.policy?.quiet != true && !dev.forgesworn.kithmoot.session.isDmPolicy(room.policy)

    @Synchronized fun seed(room: SavedRoom, now: Long): SavedRoomLogo? {
        var entry = held[room.id]
        if (!eligible(room, now)) {
            forget(room.id)
            return null
        }
        if (entry != null && (entry.scope != scope(room) || entry.ends?.let { it <= now } == true)) {
            forget(room.id); entry = null
        }
        return entry?.logo ?: room.sharedLogo
    }

    @Synchronized fun remember(room: SavedRoom, record: RoomLogoRecord?, epoch: Int, now: Long): SavedRoomLogo? {
        if (!eligible(room, now)) { forget(room.id); return null }
        val previous = held[room.id]?.takeIf { it.scope == scope(room) }?.logo ?: room.sharedLogo
        if (record == null) {
            if (previous != null && previous.epoch >= epoch) return previous
            forget(room.id); return null
        }
        if (previous != null && (previous.epoch > epoch || previous.epoch == epoch &&
                dev.forgesworn.kithmoot.protocol.compareRoomLogos(previous.record, record) > 0)) return previous
        val logo = requireNotNull(SavedRoomLogo.read(SavedRoomLogo(record.copy(by = null), epoch).toJson()))
        held.remove(room.id)
        held[room.id] = Entry(scope(room), logo, room.ends)
        while (held.size > 128) held.remove(held.keys.first())
        changed.update { it + 1 }
        return logo
    }

    @Synchronized fun decorate(rooms: List<SavedRoomSummary>, now: Long): List<SavedRoomSummary> {
        val scopes = rooms.associate { it.id to it.logoScope }
        val expired = held.keys.filter { id ->
            val entry = held.getValue(id)
            scopes[id] != entry.scope || entry.ends?.let { it <= now } == true
        }
        expired.forEach(held::remove)
        if (expired.isNotEmpty()) changed.update { it + 1 }
        return rooms.map { room ->
            val entry = held[room.id]
            if (room.ended || room.endsAt?.let { it <= now } == true) room.copy(logo = null)
            else if (entry != null) room.copy(logo = entry.logo.record.image) else room
        }
    }

    @Synchronized fun forget(id: String) {
        if (held.remove(id) != null) changed.update { it + 1 }
    }
    @Synchronized fun clear() {
        if (held.isNotEmpty()) { held.clear(); changed.update { it + 1 } }
    }
}
