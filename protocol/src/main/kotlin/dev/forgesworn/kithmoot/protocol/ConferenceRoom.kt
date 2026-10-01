package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A conference room: a persistent group room that ends at a fixed time and is
 * wiped from relays when it does.
 *
 * The end time travels in the group invitation's encrypted body as `ends`
 * (unix seconds), and every event a member device signs for the room carries
 * a NIP-40 `expiration` no later than it, so a relay that honours NIP-40
 * drops the room's traffic, and the invitation that opens it, at the end.
 * The same rule as the web client's, in fold-kit.
 */

/** The longest a conference room may be set to run, in seconds. */
const val MAX_CONFERENCE_SECONDS: Long = 30L * 24 * 60 * 60

private const val MAX_SAFE_INTEGER: Long = (1L shl 53) - 1

/** A new end time must be in the future and no more than 30 days away. */
fun requireConferenceEnds(ends: Long, now: Long) {
    require(ends > now) { "a conference room must end in the future" }
    require(ends <= now + MAX_CONFERENCE_SECONDS) { "a conference room runs for at most 30 days" }
}

/** Whether a room with this end time has ended at [now]. Null never ends. */
fun conferenceEnded(ends: Long?, now: Long): Boolean = ends != null && now >= ends

/**
 * The tags with a room's end applied, NIP-40 style: an `expiration` is added
 * when there is none, an earlier one is kept, a later or unreadable one is
 * lowered to [ends], and the result never carries two. Null [ends] returns
 * [tags] unchanged, so an ordinary room's events stay byte-identical.
 */
fun withRoomExpiration(tags: List<List<String>>, ends: Long?): List<List<String>> {
    if (ends == null) return tags
    val existing = tags.filter { it.firstOrNull() == "expiration" }
    if (existing.isEmpty()) return tags + listOf(listOf("expiration", ends.toString()))
    val only = existing.singleOrNull()
    if (only != null && only.size == 2 && (only[1].toLongOrNull() ?: Long.MAX_VALUE) <= ends) return tags
    val value = minOf(ends, existing.minOf { it.getOrNull(1)?.toLongOrNull() ?: ends })
    val first = tags.indexOfFirst { it.firstOrNull() == "expiration" }
    return tags.filterIndexed { index, tag -> index == first || tag.firstOrNull() != "expiration" }
        .map { if (it.firstOrNull() == "expiration") listOf("expiration", value.toString()) else it }
}

/**
 * A body's `ends`, when it is a JSON number holding a positive safe integer;
 * null for anything else. Absent is the caller's to tell apart.
 */
internal fun conferenceEndsOf(element: JsonElement?): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.content.toBigDecimalOrNull() ?: return null
    val whole = try { number.toBigIntegerExact() } catch (_: ArithmeticException) { return null }
    if (whole.signum() <= 0 || whole.bitLength() > 63) return null
    return whole.toLong().takeIf { it <= MAX_SAFE_INTEGER }
}
