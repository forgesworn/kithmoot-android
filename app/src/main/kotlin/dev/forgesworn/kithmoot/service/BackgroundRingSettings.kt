package dev.forgesworn.kithmoot.service

import android.content.Context
import dev.forgesworn.kithmoot.storage.RoomRepository
import dev.forgesworn.kithmoot.storage.RoomStorageException

/**
 * The one switch behind [BackgroundCallListenerService]: "Ring when KithMoot
 * is closed". On by default, same as a saved room's own Ring me default in
 * `notifications/CallRingSettings.kt` - this decides whether anything
 * listens in the background at all, that decides which rooms it listens to.
 *
 * [enabled] migrates a pre-existing install the first time it is read: a
 * phone that never touched this switch has no stored value, and the
 * migration writes `true` explicitly rather than leaving it to the default
 * parameter, so the stored state and the switch shown in Settings always
 * agree. An install that explicitly turned it off keeps that choice.
 */
class BackgroundRingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kithmoot.background_ring.v1", Context.MODE_PRIVATE)

    fun enabled(): Boolean = migrateBackgroundRingEnabled(
        hasStoredValue = prefs.contains(KEY_ENABLED),
        storedValue = prefs.getBoolean(KEY_ENABLED, true),
        write = { prefs.edit().putBoolean(KEY_ENABLED, it).apply() },
    )

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** True the first time only: the battery exemption is asked for once, ever. */
    fun takeBatteryAsk(): Boolean {
        if (prefs.getBoolean("batteryAsked", false)) return false
        prefs.edit().putBoolean("batteryAsked", true).apply()
        return true
    }

    /**
     * Whether to ask, now, for calls to take the screen, and if so records
     * the ask. The first time in a room; after that only once a call has
     * rung without taking the screen, and never more than once a week. See
     * [fullScreenAskDue].
     */
    fun takeFullScreenAsk(nowMs: Long = System.currentTimeMillis()): Boolean {
        val askedAt = when {
            prefs.contains(KEY_FULL_SCREEN_ASKED_AT) -> prefs.getLong(KEY_FULL_SCREEN_ASKED_AT, 0)
            // Asked by a build that kept only a flag: when is not known.
            prefs.getBoolean("fullScreenAsked", false) -> 0L
            else -> null
        }
        val missedAt = prefs.getLong(KEY_FULL_SCREEN_MISSED_AT, -1).takeIf { it >= 0 }
        if (!fullScreenAskDue(askedAt, missedAt, nowMs)) return false
        prefs.edit().putLong(KEY_FULL_SCREEN_ASKED_AT, nowMs).apply()
        return true
    }

    /** A call has just rung as a notification only, because calls may not take the screen. */
    fun noteRangWithoutScreen(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_FULL_SCREEN_MISSED_AT, nowMs).apply()
    }

    /** Android's full-screen page was opened from KithMoot, so a refusal that
     *  outlives it is worth explaining (sideloaded apps' restricted settings). */
    var fullScreenSettingsTried: Boolean
        get() = prefs.getBoolean(KEY_FULL_SCREEN_TRIED, false)
        set(value) { prefs.edit().putBoolean(KEY_FULL_SCREEN_TRIED, value).apply() }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FULL_SCREEN_ASKED_AT = "fullScreenAskedAtMs"
        private const val KEY_FULL_SCREEN_MISSED_AT = "fullScreenMissedAtMs"
        private const val KEY_FULL_SCREEN_TRIED = "fullScreenSettingsTried"
    }
}

/** The least time between two asks for full-screen calls. */
internal const val FULL_SCREEN_ASK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

/**
 * The full-screen ask's rule, pure for testing: asked never, ask; otherwise
 * only after a call rang without taking the screen since the last ask, and
 * at least [FULL_SCREEN_ASK_INTERVAL_MS] after it. [askedAt] and [missedAt]
 * are milliseconds, null when it has not happened.
 */
internal fun fullScreenAskDue(askedAt: Long?, missedAt: Long?, now: Long): Boolean = when {
    askedAt == null -> true
    missedAt == null || missedAt <= askedAt -> false
    else -> now - askedAt >= FULL_SCREEN_ASK_INTERVAL_MS
}

/**
 * Saved room ids, or none when the rooms cannot be read: storage locked or
 * damaged, or a retained preview identity still awaiting the person's
 * decision. Ringing is never worth crashing the app, the service or a boot
 * broadcast over; the next reconcile tries again.
 */
internal fun savedRoomIdsOrNone(rooms: RoomRepository): List<String> =
    try {
        rooms.list().map { it.id }
    } catch (_: RoomStorageException) {
        emptyList()
    }
