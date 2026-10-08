package dev.forgesworn.kithmoot.session

import java.time.ZoneId
import java.util.Locale

/**
 * A room that self-destructs: when it ends, every member's device deletes
 * what it wrote there and forgets the room. This file is what the app decides
 * about one without a screen: the countdown's stage and words, and what the
 * room's details, its heads-up and its tombstone row say. A port of the web
 * client's app/src/self-destruct.ts, word for word, so both say the same.
 *
 * The flag rides inside the room's encrypted group invitation and its closing
 * rekey (fold-kit 0.9.0, `destruct`); `SavedRoom.destruct` keeps it so a device
 * offline at the end still acts on it when the app next starts. What the
 * tidy-up does is `RoomSelfDestruct`.
 */
enum class CountdownStage { GREEN, AMBER, RED, FINAL, GONE }

/** The last stretch, whatever the room's lifetime: a banner across the chat. */
const val FINAL_SECONDS = 60L

data class CountdownThresholds(val amber: Double, val red: Double)

/**
 * Where amber and red start, in seconds before the end. They scale with the
 * room's lifetime so a one-day room is not amber from the start: amber is the
 * smaller of a day and a quarter of the lifetime, red the smaller of an hour
 * and a twentieth. A lifetime that is not known (a room joined on a device
 * that never saw it made) is taken as long, which gives a day and an hour.
 */
fun countdownThresholds(lifetime: Long?): CountdownThresholds {
    val life = lifetime?.takeIf { it > 0 }?.toDouble() ?: Double.POSITIVE_INFINITY
    return CountdownThresholds(amber = minOf(86_400.0, life / 4), red = minOf(3_600.0, life / 20))
}

/** The stage a room is in, [remaining] seconds before its end. */
fun countdownStage(remaining: Long, lifetime: Long?): CountdownStage {
    if (remaining <= 0) return CountdownStage.GONE
    if (remaining <= FINAL_SECONDS) return CountdownStage.FINAL
    val (amber, red) = countdownThresholds(lifetime)
    if (remaining <= red) return CountdownStage.RED
    if (remaining <= amber) return CountdownStage.AMBER
    return CountdownStage.GREEN
}

private fun plural(n: Long, one: String, many: String = "${one}s") = "$n ${if (n == 1L) one else many}"

/** How long is left, as the pill shows it: "4 days", "1 day 5 h", "5 h 12 m",
 *  "12 m", and a live clock ("42:07") once the room is red. */
fun remainingWords(remaining: Long, stage: CountdownStage): String {
    val r = maxOf(0L, remaining)
    if (stage == CountdownStage.RED || stage == CountdownStage.FINAL || stage == CountdownStage.GONE) {
        return "${r / 60}:${(r % 60).toString().padStart(2, '0')}"
    }
    val days = r / 86_400
    val hours = (r % 86_400) / 3_600
    val minutes = (r % 3_600) / 60
    if (days >= 2) return plural(days, "day")
    if (days == 1L) return if (hours > 0) "1 day $hours h" else "1 day"
    if (hours > 0) return "$hours h $minutes m"
    return "${maxOf(1L, minutes)} m"
}

/** The same, in words a screen reader says: "5 hours 12 minutes". */
fun remainingSpoken(remaining: Long): String {
    val r = maxOf(0L, remaining)
    val days = r / 86_400
    val hours = (r % 86_400) / 3_600
    val minutes = (r % 3_600) / 60
    val seconds = r % 60
    if (days >= 2) return plural(days, "day")
    if (days == 1L) return if (hours > 0) "1 day ${plural(hours, "hour")}" else "1 day"
    if (hours > 0) return if (minutes > 0) "${plural(hours, "hour")} ${plural(minutes, "minute")}" else plural(hours, "hour")
    if (minutes > 0) return if (seconds > 0 && minutes < 5) "${plural(minutes, "minute")} ${plural(seconds, "second")}" else plural(minutes, "minute")
    return plural(seconds, "second")
}

/** The prominent clock keeps seconds visible at every stage. */
fun countdownClock(remaining: Long): String {
    val seconds = maxOf(0, remaining)
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return (if (days > 0) "${days}d " else "") + listOf(hours, minutes, seconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
}

fun fuseRemaining(endsAt: Long, startsAt: Long?, now: Long): Float =
    ((endsAt - now).toDouble() / (roomLifetime(endsAt, startsAt) ?: 86_400L)).coerceIn(0.0, 1.0).toFloat()

data class Countdown(
    val stage: CountdownStage,
    /** The pill's visible words: "Self-destructs in 5 h 12 m", "Ends in 4 days". */
    val text: String,
    /** Its accessible name: "Self-destructs in 5 hours 12 minutes". */
    val spoken: String,
    /** True for a room that self-destructs; false for one that ends and keeps
     *  a read-only copy, whose pill is a neutral grey. */
    val destruct: Boolean,
)

/** A room's lifetime in seconds, when when it started is known and before its end. */
fun roomLifetime(endsAt: Long, startsAt: Long?): Long? = startsAt?.takeIf { it < endsAt }?.let { endsAt - it }

/** The countdown for a room that ends at [endsAt], as of [now]. */
fun countdown(endsAt: Long, startsAt: Long?, destruct: Boolean, now: Long): Countdown {
    val remaining = endsAt - now
    val stage = countdownStage(remaining, roomLifetime(endsAt, startsAt))
    val verb = if (destruct) "Self-destructs" else "Ends"
    if (stage == CountdownStage.GONE) {
        val text = if (destruct) "Self-destructed" else "Ended"
        return Countdown(stage, text, text, destruct)
    }
    return Countdown(stage, "$verb in ${remainingWords(remaining, stage)}", "$verb in ${remainingSpoken(remaining)}", destruct)
}

/** The pill's accessible name, changing only by the minute once the clock
 *  ticks, so somebody listening to it is not interrupted every second. */
fun countdownAccessibleName(endsAt: Long, startsAt: Long?, destruct: Boolean, now: Long): String {
    val c = countdown(endsAt, startsAt, destruct, now)
    if (c.stage != CountdownStage.RED && c.stage != CountdownStage.FINAL) return c.spoken
    val minutes = maxOf(1L, (endsAt - now + 59) / 60)
    return "${if (destruct) "Self-destructs" else "Ends"} in under $minutes ${if (minutes <= 1) "minute" else "minutes"}"
}

/** What a screen reader is told when a self-destructing room enters a stage:
 *  once per stage, never per tick. Null for green and gone. */
fun stageAnnouncement(stage: CountdownStage, remaining: Long): String? = when (stage) {
    CountdownStage.AMBER, CountdownStage.RED -> "This room self-destructs in ${remainingSpoken(remaining)}."
    CountdownStage.FINAL -> "This room self-destructs in under a minute. Save anything you need now."
    else -> null
}

/** The final minute's banner across the chat. */
fun finalBannerText(remaining: Long): String =
    "This room self-destructs in ${remainingWords(remaining, CountdownStage.FINAL)}. Save anything you need now."

/** The heads-up at the start of red, through the notification settings. */
fun headsUpText(roomLabel: String, remaining: Long): String = "$roomLabel self-destructs in ${remainingSpoken(remaining)}."

/** A message still waiting to leave when the room self-destructs. */
const val WILL_NOT_BE_SENT = "Will not be sent: the room is self-destructing"

/** What the room says once it has gone. */
const val SELF_DESTRUCTED_MESSAGE = "This room self-destructed."

/** Said once, in the room's details, and never more. */
const val DESTRUCT_PROMISE = "When this room self-destructs, KithMoot deletes it from every member’s devices and asks the relays to delete its messages. " +
    "Someone could still have kept a copy, and some relays ignore deletion requests."

/** The room's details: when it self-destructs, then the promise, said honestly. */
fun destructDetails(endsAt: Long?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    (if (endsAt != null) "This room self-destructs ${conferenceEndLabel(endsAt, zone, locale)}. " else "This room self-destructs if it is ended for everyone. ") +
        DESTRUCT_PROMISE

/** A tombstone row's words. It names no room (owner decision D2). */
fun tombstoneText(at: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    "A room self-destructed · ${conferenceEndLabel(at, zone, locale)}"

/**
 * A pending message in a self-destructing room that cannot leave before the
 * end: the final minute, for anything not already on its way. Shown as
 * [WILL_NOT_BE_SENT].
 */
fun pendingDoomed(endsAt: Long?, destruct: Boolean, sending: Boolean, now: Long): Boolean =
    destruct && endsAt != null && !sending && endsAt - now <= FINAL_SECONDS
