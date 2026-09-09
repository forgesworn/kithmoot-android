package dev.forgesworn.kithmoot.protocol

import java.net.URI

/**
 * The lane a message actually travelled, and what that lane delivers.
 *
 * Three states, fixed in meaning. A client shows the lane a message took,
 * never the lane it asked for, and never claims more than the lane gives.
 * The state is worked out from where the bytes went, so nothing on the wire
 * can assert it. Mirrors `src/lane.ts` in the reference implementation.
 */
enum class Lane(val label: String, val glyph: String, val meaning: String, internal val rank: Int) {
    /** A relay operator could see who this was for and when. */
    PUBLIC("public", "○", "A relay operator could see who this was for and when.", 0),
    /** No operator outside your circle saw this. */
    SHELTERED("sheltered", "◐", "No operator outside your circle saw this.", 1),
    /** Only the two devices were involved. */
    DIRECT("direct", "●", "Only the two devices were involved.", 2);

    /** Glyph and word together, so the state reads without colour. */
    val chip: String get() = "$glyph $label"
}

/**
 * The lane one relay URL puts a message on. [circle] is the set of relay URLs
 * the client knows to be boxes of the person's own circle, from a contact card
 * or the keeper's claim; a relay in it is sheltered and every other relay,
 * onion or not, is public, because an onion hides the client's address and
 * says nothing about who runs the relay.
 */
fun laneOfRelayUrl(url: String, circle: Set<String> = emptySet()): Lane {
    if (url in circle) return Lane.SHELTERED
    val norm = try { normaliseRelay(url) } catch (_: Exception) { null }
    return if (norm != null && circle.any { runCatching { normaliseRelay(it) }.getOrNull() == norm }) Lane.SHELTERED else Lane.PUBLIC
}

private fun normaliseRelay(url: String): String {
    val u = URI(url)
    val host = u.host?.lowercase() ?: throw IllegalArgumentException("no host")
    val port = if (u.port == -1) "" else ":${u.port}"
    val path = u.path?.trimEnd('/') ?: ""
    return "${u.scheme?.lowercase()}://$host$port$path"
}

/** The weakest of several lanes, or null when there are none. */
fun weakestLane(lanes: Collection<Lane>): Lane? = lanes.minByOrNull { it.rank }

/** The lane a message takes when it is published to all of these relays. */
fun laneOfRelays(urls: Collection<String>, circle: Set<String> = emptySet()): Lane? = weakestLane(urls.map { laneOfRelayUrl(it, circle) })

/** A message went by a weaker lane than the one asked for. */
fun isDowngrade(requested: Lane, actual: Lane): Boolean = actual.rank < requested.rank
