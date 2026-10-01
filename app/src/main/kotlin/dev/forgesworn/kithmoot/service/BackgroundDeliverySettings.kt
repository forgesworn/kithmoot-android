package dev.forgesworn.kithmoot.service

import android.content.Context

/**
 * "Receive messages when KithMoot is closed": on by default, so a message
 * reaches the phone when it is sent rather than when KithMoot is next opened.
 * It keeps a connection per saved room open in the background; there is no
 * push server to do it instead. The last state the
 * service reported is kept beside it, with whether the service was running,
 * so the next launch can tell a force-stop or crash from a clean stop.
 */
class BackgroundDeliverySettings(context: Context) {
    private val prefs = context.getSharedPreferences("kithmoot.background_delivery.v1", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun state(): DeliveryState =
        prefs.getString(KEY_STATE, null)?.let { runCatching { DeliveryState.valueOf(it) }.getOrNull() } ?: DeliveryState.OFF

    fun stateAt(): Long = prefs.getLong(KEY_STATE_AT, 0)

    /** Written synchronously: a force-stop can follow at any moment. */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun report(state: DeliveryState, running: Boolean, at: Long = System.currentTimeMillis() / 1000) {
        prefs.edit().putString(KEY_STATE, state.name).putLong(KEY_STATE_AT, at).putBoolean(KEY_RUNNING, running).commit()
    }

    fun wasRunning(): Boolean = prefs.getBoolean(KEY_RUNNING, false)

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_STATE = "state"
        const val KEY_STATE_AT = "stateAt"
        const val KEY_RUNNING = "running"
    }
}
