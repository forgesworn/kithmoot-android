package dev.forgesworn.kithmoot.service

import android.content.Context

/**
 * The one opt-in switch behind [BackgroundCallListenerService]: "Ring when
 * KithMoot is closed". Off by default. One switch for the whole app, unlike
 * the per-room Ring me / Notify quietly / Nothing in
 * `notifications/CallRingSettings.kt` - this decides whether anything
 * listens in the background at all, that decides which rooms it listens to.
 */
class BackgroundRingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kithmoot.background_ring.v1", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean("enabled", false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
    }
}
