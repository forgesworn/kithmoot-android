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

    companion object {
        private const val KEY_ENABLED = "enabled"
    }
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
