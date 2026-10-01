package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.MAX_INVITATION_RELAYS
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

    /** At most this many relays ride in any link: an invitation, a fresh
     *  link after rotation, or a pairing link. Matches the web client's
     *  `MAX_RELAY_HINTS`. */
    const val MAX_LINK: Int = MAX_INVITATION_RELAYS

    /**
     * The relays a link names, from those this device's [pool] actually
     * uses: the room's own relays first, then the rest, cut at [MAX_LINK].
     * Duplicates collapse by canonical form. A room relay the pool does not
     * use (one a Tor-only or sheltered room turned away) is never named. A
     * room with more than eight relays of its own fills the link with them;
     * this device's own relays are what the cap cuts.
     */
    fun forLink(pool: List<String>, room: List<String>): List<String> {
        val roomKeys = room.map(::canonicalOrSelf).toSet()
        val seen = mutableSetOf<String>()
        val (shared, own) = pool.partition { canonicalOrSelf(it) in roomKeys }
        return (shared + own).filter { seen.add(canonicalOrSelf(it)) }.take(MAX_LINK)
    }

    /** The room's relays split by what this room's guard lets it use. */
    data class Guarded(val accepted: List<String>, val refused: List<String>)

    /**
     * Splits the room's relays by [accepts]: an anonymous room accepts only
     * v3 onion relays (`TorOnlyRelayUrls`), a room sheltered behind a Bothy
     * only its own Link route and its circle's relays. A null guard is an
     * ordinary room, which uses them all. Refused relays are never put in the
     * pool, so the guard is never weakened; the room says how many it left
     * out ([refusalNotice]).
     */
    fun guarded(room: List<String>, accepts: ((String) -> Boolean)?): Guarded {
        if (accepts == null) return Guarded(room, emptyList())
        val (accepted, refused) = room.partition { url -> runCatching { accepts(url) }.getOrDefault(false) }
        return Guarded(accepted, refused)
    }

    /** What a guarded room says about the room relays it does not use, or
     *  null when it uses them all. */
    fun refusalNotice(refused: Int, torOnly: Boolean): String? {
        if (refused <= 0) return null
        val what = if (refused == 1) "1 room relay isn't" else "$refused room relays aren't"
        val why = if (torOnly) "reachable over Tor" else "in your circle"
        return "$what $why, so you may miss people who use only ${if (refused == 1) "that one" else "those"}."
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
