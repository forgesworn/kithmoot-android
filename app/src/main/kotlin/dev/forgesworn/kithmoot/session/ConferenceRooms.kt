package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.requireConferenceEnds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How long a new group room runs: the choice the new-room form offers.
 * [NEVER] is an ordinary persistent room; anything else is a conference room,
 * which ends at a fixed time and is wiped from relays when it does. See
 * `protocol/ConferenceRoom.kt`.
 */
enum class ConferenceLength(val days: Int, val label: String) {
    NEVER(0, "Never"),
    ONE_DAY(1, "After 1 day"),
    THREE_DAYS(3, "After 3 days"),
    SEVEN_DAYS(7, "After 7 days");

    /** The end time a room started at [now] would have, or null for [NEVER]. */
    fun endsFrom(now: Long): Long? =
        if (this == NEVER) null else (now + days * 24L * 60 * 60).also { requireConferenceEnds(it, now) }
}

/** "Sat 4 Oct, 18:00": a conference room's end, in this device's zone. */
fun conferenceEndLabel(ends: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", locale).withZone(zone).format(Instant.ofEpochSecond(ends))

/** "Ends Sat 4 Oct, 18:00", for the room details and the invite sheet. */
fun conferenceEndsLine(ends: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    "Ends ${conferenceEndLabel(ends, zone, locale)}"

/** What opening an ended conference room, or its link, says. */
fun conferenceEndedMessage(ends: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    "This conference room ended on ${conferenceEndLabel(ends, zone, locale)}."
