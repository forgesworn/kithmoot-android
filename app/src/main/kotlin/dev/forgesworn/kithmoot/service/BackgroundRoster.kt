package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.protocol.RosterEntry

/**
 * A minimal, receive-only roster for one room: enough to say whether a call
 * is on and who started it, nothing else `session.RoomSession` tracks - no
 * announce-and-respond, no chat, no role arbitration, and nothing is ever
 * published from it. Entries expire the same way `RoomSession`'s presence
 * does (`SessionTiming.presenceTtlSeconds`), so a device that goes quiet
 * stops counting as being on the call.
 */
class BackgroundRoster(private val ttlSeconds: Long = 75) {
    private val entries = linkedMapOf<String, RosterEntry>()
    private val seenAt = mutableMapOf<String, Long>()

    fun accept(entry: RosterEntry, now: Long) {
        entries[entry.device] = entry
        seenAt[entry.device] = now
    }

    /** The current roster at [now], entries older than the TTL dropped. */
    fun current(now: Long): List<RosterEntry> {
        val expired = seenAt.filterValues { now - it > ttlSeconds }.keys.toList()
        expired.forEach { entries.remove(it); seenAt.remove(it) }
        return entries.values.toList()
    }
}
