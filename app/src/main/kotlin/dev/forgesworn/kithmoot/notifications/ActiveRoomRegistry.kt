package dev.forgesworn.kithmoot.notifications

import java.util.Collections

/**
 * Room ids this process currently has a `RoomViewModel` open for: the call
 * room and, beside it, a visited chat-only room (see `MainActivity`'s
 * `visitor` instance).
 *
 * The background call listener (`service/BackgroundCallListenerService.kt`)
 * skips every room named here - that room's own `IncomingCallRingCoordinator`
 * already rings for it over the live connection, and ringing twice for the
 * same call would be worse than the background listener staying quiet.
 */
object ActiveRoomRegistry {
    private val open = Collections.synchronizedSet(mutableSetOf<String>())

    fun mark(roomId: String) {
        if (roomId.isNotEmpty()) open.add(roomId)
    }

    fun unmark(roomId: String) {
        open.remove(roomId)
    }

    fun isOpen(roomId: String): Boolean = open.contains(roomId)
}
