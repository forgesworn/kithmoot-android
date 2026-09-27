package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl

/**
 * The relays a saved room opens with. A room's history lives on the relays
 * its links name, not on the ones this device happened to save first, so a
 * saved room reads the union: its saved relays, then every relay named by
 * its own link, the account's bookmark for it, or the invitation it is being
 * opened from. Matches the web client, which reads a room's link hints every
 * time it opens one.
 */
object RoomRelays {
    const val MAX: Int = 16

    /** Rooms made before the third public fallback joined the defaults name
     *  only this exact pair. They get the current third route at open time. */
    private val LEGACY_PUBLIC = setOf("wss://nos.lol", "wss://relay.primal.net")
    const val PUBLIC_FALLBACK: String = "wss://nostr.mom"

    fun atOpen(saved: List<String>, linked: List<List<String>>): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        // Saved relays stay first and unchanged; they were validated on save.
        for (url in saved) if (seen.add(canonicalOrSelf(url))) out += url
        for (url in linked.flatten()) {
            if (out.size >= MAX) break
            val key = runCatching { canonicalRelayUrl(url) }.getOrNull() ?: continue
            if (seen.add(key)) out += url
        }
        return withPublicFallback(out)
    }

    fun withPublicFallback(relays: List<String>): List<String> {
        val canonical = relays.map(::canonicalOrSelf).toSet()
        return if (relays.size == LEGACY_PUBLIC.size && canonical == LEGACY_PUBLIC) relays + PUBLIC_FALLBACK else relays
    }

    private fun canonicalOrSelf(url: String): String = runCatching { canonicalRelayUrl(url) }.getOrDefault(url)
}
