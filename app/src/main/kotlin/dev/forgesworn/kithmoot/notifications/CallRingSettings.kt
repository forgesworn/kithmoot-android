package dev.forgesworn.kithmoot.notifications

import android.content.Context

/** What an incoming call in a room should do on this device. */
enum class CallRingMode(val label: String, val storeValue: String) {
    /** Ring loudly with the incoming-call screen: the default. */
    RING("Ring me", "ring"),
    /** A heads-up notification with no sound or vibration. */
    QUIET("Notify quietly", "quiet"),
    /** No notification at all; the call still shows once the room is open. */
    NOTHING("Nothing", "nothing");

    companion object {
        fun from(value: String?): CallRingMode = entries.find { it.storeValue == value } ?: RING
    }
}

/**
 * Per-room choice of how an incoming call behaves, stored locally on this
 * device only. Mirrors the room-scoped override the web client already
 * keeps for message notifications (see `notification-scopes.ts`), kept in
 * its own preference file since it answers a narrower question: whether a
 * call in a room this device already has notifications for should ring.
 */
class CallRingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kithmoot.call_ring.v1", Context.MODE_PRIVATE)

    fun modeFor(roomId: String): CallRingMode = CallRingMode.from(prefs.getString(roomId, null))

    fun setMode(roomId: String, mode: CallRingMode) {
        prefs.edit().putString(roomId, mode.storeValue).apply()
    }
}
