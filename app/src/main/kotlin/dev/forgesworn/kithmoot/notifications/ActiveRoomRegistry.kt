package dev.forgesworn.kithmoot.notifications

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
    // Counted, not a set: a room closed and reopened at once may see the
    // close's unmark land after the reopen's mark.
    private val open = mutableMapOf<String, Int>()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

    fun mark(roomId: String) {
        if (roomId.isEmpty()) return
        val first = synchronized(open) { open.merge(roomId, 1, Int::plus) == 1 }
        if (first) listeners.forEach { it(roomId) }
    }

    fun unmark(roomId: String) {
        val last = synchronized(open) {
            val count = open[roomId] ?: return
            if (count <= 1) { open.remove(roomId); true } else { open[roomId] = count - 1; false }
        }
        if (last) listeners.forEach { it(roomId) }
    }

    /** The background service hands a room over at once rather than at its next tick. */
    fun listen(listener: (String) -> Unit) { listeners.add(listener) }

    fun unlisten(listener: (String) -> Unit) { listeners.remove(listener) }

    fun isOpen(roomId: String): Boolean = synchronized(open) { open.containsKey(roomId) }
}
