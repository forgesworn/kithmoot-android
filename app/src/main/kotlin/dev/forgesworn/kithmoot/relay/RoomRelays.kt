package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl

/**
 * The relays a saved room opens with. A room's history lives on the relays
 * its links name, not on the ones this device happened to save first, so a
 * saved room reads the union: its saved relays, then every relay named by
 * its own link, the account's bookmark for it, or the invitation it is being
 * opened from. Matches the web client, which reads a room's link hints every
 * time it opens one.
 *
 * The room's own relays come before all of them and are never cut: the
 * relays it was made on (`SavedRoom.roomRelays`) and any its authority added
 * since. Every member's pool includes them, which is what puts any two
 * members on at least one relay in common. This device's own relays fill the
 * rest, up to [MAX], and are the ones the cap cuts. Mirrors the web client's
 * room-relay layer in `RelayConnections` (app/src/relay-settings.ts).
 */
object RoomRelays {
    const val MAX: Int = 16

    /** Rooms made before the third public fallback joined the defaults name
     *  only this exact pair. They get the current third route at open time. */
    private val LEGACY_PUBLIC = setOf("wss://nos.lol", "wss://relay.primal.net")
    const val PUBLIC_FALLBACK: String = "wss://nostr.mom"

    fun atOpen(saved: List<String>, linked: List<List<String>>, room: List<String> = emptyList()): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        // The room's relays first, all of them, in the form this device
        // already saved a relay in when it has one, so a pool never opens
        // two sockets to one relay.
        for (url in room) {
            val key = runCatching { canonicalRelayUrl(url) }.getOrNull() ?: continue
            if (seen.add(key)) out += saved.firstOrNull { canonicalOrSelf(it) == key } ?: url
        }
        val limit = maxOf(MAX, out.size)
        // Saved relays next and unchanged; they were validated on save.
        for (url in saved) {
            if (out.size >= limit) break
            if (seen.add(canonicalOrSelf(url))) out += url
        }
        for (url in linked.flatten()) {
            if (out.size >= limit) break
            val key = runCatching { canonicalRelayUrl(url) }.getOrNull() ?: continue
            if (seen.add(key)) out += url
        }
        return withPublicFallback(out)
    }

    fun withPublicFallback(relays: List<String>): List<String> {
        val canonical = relays.map(::canonicalOrSelf).toSet()
        return if (relays.size == LEGACY_PUBLIC.size && canonical == LEGACY_PUBLIC) relays + PUBLIC_FALLBACK else relays
    }

    /** The relays in [next] that [current] does not already have, by
     *  canonical form: what a live pool still has to add. */
    fun missing(current: List<String>, next: List<String>): List<String> {
        val have = current.map(::canonicalOrSelf).toSet()
        return next.filter { canonicalOrSelf(it) !in have }
    }

    /** The relays in [pool] that are one of [room], by canonical form. */
    fun ofRoom(pool: List<String>, room: List<String>): Set<String> {
        val keys = room.map(::canonicalOrSelf).toSet()
        return pool.filter { canonicalOrSelf(it) in keys }.toSet()
    }

    private fun canonicalOrSelf(url: String): String = runCatching { canonicalRelayUrl(url) }.getOrDefault(url)
}
