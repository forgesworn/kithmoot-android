package dev.forgesworn.kithmoot.media

import android.content.SharedPreferences

/**
 * A tiny float key-value store, so call-volume persistence can be tested
 * without Android's `SharedPreferences` (a plain unit test's stub of it
 * always returns defaults - see `testOptions.unitTests.returnDefaultValues`
 * in `app/build.gradle.kts`).
 */
interface VolumeStore {
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun remove(key: String)
}

/**
 * Backs a [VolumeStore] with the app's ordinary per-device display
 * preferences - the same `kithmoot.display` store `ui/theme/TextSize.kt`
 * uses for the same kind of setting: local, not secret, remembered on this
 * device only.
 */
class SharedPreferencesVolumeStore(private val prefs: SharedPreferences) : VolumeStore {
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * Per-person call volume: a linear gain applied to every remote audio track
 * from one participant's devices, remembered on this device only.
 *
 * Mirrors the web client's rule (`app/src/volume-store.ts` and
 * `remote-volume.ts` in the web repo): 1.0 (100%) is untouched and is never
 * written, and a level outside 0.0 to 2.0 is clamped rather than trusted.
 *
 * This is only ever a multiplier on top of the room's own rules about which
 * tracks are allowed to play at all - one of a person's own devices, on echo
 * prevention, and any device that has left the call are both decided
 * elsewhere (see the `remoteTracks` combine in `RoomViewModel.startMedia`)
 * and always win: a gain set here cannot bring back a track those rules
 * silenced, because a disabled track renders no audio regardless of volume.
 */
class CallVolume(private val store: VolumeStore) {

    /** This device's remembered level for `participant`, or the untouched
     *  default when nothing has been set, the key is not a participant
     *  pubkey, or what is stored cannot be trusted. */
    fun gainFor(participant: String): Float =
        if (!isParticipant(participant)) DEFAULT_GAIN else clamp(store.getFloat(keyFor(participant), DEFAULT_GAIN))

    /** Remembers `gain` for `participant`, or forgets it at exactly the
     *  default - the untouched case is not worth a line in storage, and
     *  this is also how a slider dragged back to the middle clears
     *  whatever was there before. */
    fun setGain(participant: String, gain: Float) {
        if (!isParticipant(participant)) return
        val clamped = clamp(gain)
        if (clamped == DEFAULT_GAIN) store.remove(keyFor(participant)) else store.putFloat(keyFor(participant), clamped)
    }

    /** Forgets this device's level for `participant`. Call wherever the app
     *  already forgets local data tied to a participant, such as forgetting
     *  their contact card. */
    fun forget(participant: String) {
        if (isParticipant(participant)) store.remove(keyFor(participant))
    }

    companion object {
        const val MIN_GAIN = 0.0f
        const val DEFAULT_GAIN = 1.0f
        const val MAX_GAIN = 2.0f

        private const val KEY_PREFIX = "callVolume:"
        private val PARTICIPANT = Regex("^[0-9a-f]{64}$")

        internal fun keyFor(participant: String): String = KEY_PREFIX + participant
        internal fun isParticipant(participant: String): Boolean = PARTICIPANT.matches(participant)

        fun clamp(gain: Float): Float = gain.coerceIn(MIN_GAIN, MAX_GAIN)

        /** 0-200% <-> 0.0-2.0 gain, rounded to the nearest whole percent. */
        fun gainToPercent(gain: Float): Int = Math.round(clamp(gain) * 100f)
        fun percentToGain(percent: Int): Float = clamp(percent / 100f)
    }
}
