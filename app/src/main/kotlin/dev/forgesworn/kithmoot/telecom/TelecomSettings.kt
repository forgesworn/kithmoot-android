package dev.forgesworn.kithmoot.telecom

import android.content.Context

/**
 * The kill switch for [CallTelecom]: "Phone call controls" in notification
 * settings. Read each time a call rings or is joined, so turning it off takes
 * effect on the next call without a release; a call already under way keeps
 * whatever it started with.
 *
 * On by default. An OEM whose Telecom misbehaves is the reason it exists.
 */
class TelecomSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kithmoot.telecom.v1", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    companion object {
        const val DEFAULT_ENABLED = true
        private const val KEY_ENABLED = "enabled"
    }
}
