package dev.forgesworn.kithmoot.notifications

import android.content.Context
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.mentionedBy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Which messages in a room tell this person, and for how long a room stays
 * quiet: the web's room menu (app/src/notification-scopes.ts, kithmoot #496)
 * in the same words. A mute is separate from the mode, so it ends back at the
 * mode chosen; Nothing is a mute with no end. Kept on this phone only.
 */

/** A room's choice, as the menu offers it. */
enum class RoomNotifyMode(val label: String, val storeValue: String) {
    ALL("Every message", "all"),
    MENTIONS("Only mentions and replies", "mentions"),
    NOTHING("Nothing", "off"),
    DEFAULT("Use my default", "inherit");

    companion object {
        fun from(value: String?): RoomNotifyMode = entries.find { it.storeValue == value } ?: DEFAULT
        /** What the default itself can be: Use my default has nothing above it. */
        val DEFAULTS = listOf(ALL, MENTIONS, NOTHING)
        /** A saved default, which is Every message unless changed. */
        fun defaultFrom(value: String?): RoomNotifyMode = DEFAULTS.find { it.storeValue == value } ?: ALL
    }
}

/** What a room's notifications come to, once its mute, its own choice and the default are applied. */
enum class NotifyLevel { ALL, MENTIONS, NOTHING }

/** A muted room: set to Nothing, which has no end, or muted until a time. */
sealed interface RoomMute {
    data object Always : RoomMute
    data class Until(val until: Long) : RoomMute
}

/** The four lengths of a timed mute. */
enum class MuteLength(val label: String, val seconds: Long) {
    HOUR("Mute for 1 hour", 3_600),
    EIGHT_HOURS("Mute for 8 hours", 28_800),
    DAY("Mute for 1 day", 86_400),
    WEEK("Mute for 1 week", 604_800),
}

/** Whether a room is muted now: set to Nothing, or a timed mute still running. */
fun roomMute(mode: RoomNotifyMode, until: Long?, now: Long): RoomMute? = when {
    mode == RoomNotifyMode.NOTHING -> RoomMute.Always
    until != null && until > now -> RoomMute.Until(until)
    else -> null
}

/** A timed mute, then the room's own choice, then the default. A room left
 *  on Use my default under a default of Nothing is quiet but not muted. */
fun notifyLevel(mode: RoomNotifyMode, until: Long?, default: RoomNotifyMode, now: Long): NotifyLevel {
    if (roomMute(mode, until, now) != null) return NotifyLevel.NOTHING
    return when (if (mode == RoomNotifyMode.DEFAULT) default else mode) {
        RoomNotifyMode.ALL, RoomNotifyMode.DEFAULT -> NotifyLevel.ALL
        RoomNotifyMode.MENTIONS -> NotifyLevel.MENTIONS
        RoomNotifyMode.NOTHING -> NotifyLevel.NOTHING
    }
}

/** Whether a message is aimed at [self]: a reply to one of theirs, or a mention of them or of everyone. */
fun addressesReader(message: ChatMessage, self: String): Boolean =
    message.reply?.participant?.hexEquals(self) == true || mentionedBy(message, self)

/** Whether [message] is worth a notification at [level]. Every message in a
 *  private chat is aimed at the reader, so Only mentions and replies lets them all through. */
fun wantsNotice(level: NotifyLevel, message: ChatMessage, self: String, privateChat: Boolean): Boolean = when (level) {
    NotifyLevel.ALL -> true
    NotifyLevel.NOTHING -> false
    NotifyLevel.MENTIONS -> privateChat || addressesReader(message, self)
}

/**
 * What a muted room says about itself, in its header and its menu: plain
 * "Muted" for Nothing, else the end of the mute. Today and tomorrow are
 * named, a weekday out to six days, then the date, with the clock as the
 * room's row writes its times (the phone's 12 or 24-hour setting).
 */
fun mutedUntilText(mute: RoomMute, now: Long, zone: ZoneId, locale: Locale = Locale.UK, is24Hour: Boolean = true): String {
    if (mute !is RoomMute.Until) return "Muted"
    val end = Instant.ofEpochSecond(mute.until).atZone(zone)
    val days = ChronoUnit.DAYS.between(Instant.ofEpochSecond(now).atZone(zone).toLocalDate(), end.toLocalDate())
    val clock = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale).format(end)
    return when {
        days <= 0 -> "Muted until $clock"
        days == 1L -> "Muted until tomorrow, $clock"
        days <= 6 -> "Muted until ${DateTimeFormatter.ofPattern("EEE", locale).format(end)}, $clock"
        else -> "Muted until ${DateTimeFormatter.ofPattern("d MMM", locale).format(end)}, $clock"
    }
}

/**
 * Each room's choice and timed mute, on this phone only (backups are off for
 * every preference file). Keyed by room id, as [CallRingSettings] is; a room
 * wiped from the phone forgets both ([forget], RoomWipeStep.NOTIFICATION_SETTING).
 */
class RoomNotificationSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(roomId: String): RoomNotifyMode = RoomNotifyMode.from(prefs.getString(MODE + roomId, null))

    /** Nothing already means silent until changed, so it clears a timed mute rather than sitting over one. */
    fun setMode(roomId: String, mode: RoomNotifyMode) {
        val edit = prefs.edit()
        if (mode == RoomNotifyMode.DEFAULT) edit.remove(MODE + roomId) else edit.putString(MODE + roomId, mode.storeValue)
        if (mode == RoomNotifyMode.NOTHING) edit.remove(UNTIL + roomId)
        edit.apply()
    }

    fun mutedUntil(roomId: String): Long? = prefs.getLong(UNTIL + roomId, 0L).takeIf { it > 0L }

    fun mute(roomId: String, until: Long) { prefs.edit().putLong(UNTIL + roomId, until).apply() }

    fun unmute(roomId: String) { prefs.edit().remove(UNTIL + roomId).apply() }

    fun muteOf(roomId: String, now: Long): RoomMute? = roomMute(mode(roomId), mutedUntil(roomId), now)

    fun level(roomId: String, now: Long, default: RoomNotifyMode): NotifyLevel =
        notifyLevel(mode(roomId), mutedUntil(roomId), default, now)

    /** Every muted room: set to Nothing, or a timed mute still running. */
    fun mutes(now: Long): Map<String, RoomMute> = mutesIn(prefs.all, now)

    /** The soonest end still to come, to know when to look again. */
    fun nextEnd(now: Long): Long? = nextMuteEnd(prefs.all, now)

    /** Drops every ended mute. */
    fun prune(now: Long) {
        val ended = endedMutes(prefs.all, now)
        if (ended.isEmpty()) return
        val edit = prefs.edit()
        ended.forEach(edit::remove)
        edit.apply()
    }

    /** The room is gone from this phone. */
    fun forget(roomId: String) { prefs.edit().remove(MODE + roomId).remove(UNTIL + roomId).apply() }

    internal companion object {
        const val PREFS = "kithmoot.room_notifications.v1"
        const val MODE = "mode."
        const val UNTIL = "until."

        /** The muted rooms in the store's entries: Nothing, then timed mutes still running. */
        fun mutesIn(entries: Map<String, *>, now: Long): Map<String, RoomMute> = buildMap {
            for ((key, value) in entries) {
                if (key.startsWith(MODE) && value == RoomNotifyMode.NOTHING.storeValue) put(key.removePrefix(MODE), RoomMute.Always)
            }
            for ((key, value) in entries) {
                if (key.startsWith(UNTIL) && value is Long && value > now) putIfAbsent(key.removePrefix(UNTIL), RoomMute.Until(value))
            }
        }

        fun nextMuteEnd(entries: Map<String, *>, now: Long): Long? =
            entries.mapNotNull { (key, value) -> (value as? Long)?.takeIf { key.startsWith(UNTIL) && it > now } }.minOrNull()

        /** Keys of mutes that have ended, or that cannot be read. */
        fun endedMutes(entries: Map<String, *>, now: Long): Set<String> =
            entries.filter { (key, value) -> key.startsWith(UNTIL) && (value !is Long || value <= now) }.keys
    }
}

/** A room's level now, as the open room, the background service, a ring and
 *  the self-destruct heads-up all decide it. As on the web, Nothing (however
 *  it was reached) also keeps a call from ringing. */
fun roomNotifyLevel(context: Context, roomId: String, now: Long = System.currentTimeMillis() / 1000): NotifyLevel =
    RoomNotificationSettings(context).level(roomId, now, ChatNotifications.load(context).level)
