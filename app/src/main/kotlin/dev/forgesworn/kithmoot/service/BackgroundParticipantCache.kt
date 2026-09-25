package dev.forgesworn.kithmoot.service

import android.content.Context
import dev.forgesworn.kithmoot.session.Participant

/**
 * A small, best-effort device-to-participant mapping, remembered from the
 * last time each room was open, so a call bell that only ever names the
 * ringing device (never a participant - see `protocol/CallBell.kt`) can
 * still be shown against a name while the app is closed.
 *
 * Not a roster. It is never treated as fresh or authoritative - a device
 * that has moved to a new participant, or simply was never seen live on
 * this phone, resolves to nothing, and the caller falls back to a plain
 * "Someone in <room>" (see [BackgroundCallListenerService]). Holds no
 * secret: participant and device pubkeys are already what this room's
 * relays see on every roster event.
 */
class BackgroundParticipantCache(context: Context) {
    private val prefs = context.getSharedPreferences("kithmoot.background_participants.v1", Context.MODE_PRIVATE)

    /** Called whenever a live room sees a fresh roster snapshot. */
    fun remember(roomId: String, participants: List<Participant>) {
        if (participants.isEmpty()) return
        val editor = prefs.edit()
        for (person in participants) {
            for (entry in person.devices) editor.putString(key(roomId, entry.device), person.participant)
        }
        editor.apply()
    }

    /** The participant this device was last seen as in this room, if any. */
    fun participantFor(roomId: String, device: String): String? = prefs.getString(key(roomId, device), null)

    /** Forgets everything remembered for a room: called when a saved room is removed. */
    fun forget(roomId: String) {
        val prefix = "$roomId|"
        val editor = prefs.edit()
        for (existing in prefs.all.keys) if (existing.startsWith(prefix)) editor.remove(existing)
        editor.apply()
    }

    private fun key(roomId: String, device: String): String = "$roomId|$device"
}
