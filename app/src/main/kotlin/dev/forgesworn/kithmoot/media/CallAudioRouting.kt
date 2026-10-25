package dev.forgesworn.kithmoot.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val LOUDSPEAKER_ON_PREFERENCE = "loudspeakerOn"
private const val DISPLAY_PREFERENCES = "kithmoot.display"
private val CALL_HEADSETS = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
)

internal fun selectCallDeviceType(availableTypes: Collection<Int>, loudspeakerOn: Boolean): Int? {
    availableTypes.firstOrNull { it in CALL_HEADSETS }?.let { return it }
    val wanted = if (loudspeakerOn) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    if (wanted in availableTypes) return wanted
    return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER.takeIf { it in availableTypes }
}

/**
 * Own communication routing only while this device is sending or receiving call audio.
 *
 * With a self-managed Telecom call behind the call (see
 * `telecom/CallTelecom.kt`), Telecom owns the audio mode and audio focus and
 * does its own device switching; this then only says which device it would
 * have picked, through [TelecomRoute], so the two never both set the mode or
 * hold focus. Without one it is exactly what it always was.
 */
class CallAudioRouting(context: Context) : AutoCloseable {
    /** Asks Telecom's call for the route matching an [AudioDeviceInfo] type. */
    fun interface TelecomRoute { fun request(deviceType: Int) }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val contextExecutor = context.applicationContext.mainExecutor
    private val preferences = context.applicationContext.getSharedPreferences(DISPLAY_PREFERENCES, Context.MODE_PRIVATE)
    private val mutableLoudspeakerOn = MutableStateFlow(preferences.getBoolean(LOUDSPEAKER_ON_PREFERENCE, true))
    val loudspeakerOn: StateFlow<Boolean> get() = mutableLoudspeakerOn
    private var active = false
    private var telecom: TelecomRoute? = null
    /** Mode, focus and communication device were set here and are this
     *  class's to undo; false while Telecom holds them. */
    private var ownsSession = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { }.build()
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { synchronized(this@CallAudioRouting) { if (active) route() } }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { synchronized(this@CallAudioRouting) { if (active) route() } }
    }
    private val communicationDevice = AudioManager.OnCommunicationDeviceChangedListener {
        synchronized(this@CallAudioRouting) { if (active) route() }
    }
    private val modeChanged = AudioManager.OnModeChangedListener { mode ->
        synchronized(this@CallAudioRouting) {
            if (active && mode == AudioManager.MODE_IN_COMMUNICATION) route()
        }
    }

    @Synchronized fun setActive(wanted: Boolean) {
        if (wanted == active) return
        if (!wanted) { close(); return }
        active = true
        if (telecom == null) takeSession()
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        // A route can fall back to the earpiece without a device being
        // unplugged, including when communication mode becomes active later
        // than ICE negotiation. Hardware add/remove callbacks do not see it.
        audio.addOnCommunicationDeviceChangedListener(contextExecutor, communicationDevice)
        audio.addOnModeChangedListener(contextExecutor, modeChanged)
        route()
    }

    @Synchronized fun setLoudspeakerOn(wanted: Boolean) {
        if (mutableLoudspeakerOn.value == wanted) return
        preferences.edit().putBoolean(LOUDSPEAKER_ON_PREFERENCE, wanted).apply()
        mutableLoudspeakerOn.value = wanted
        if (active) route()
    }

    /**
     * Hands audio to a Telecom call, or takes the hand-off back with null.
     *
     * Handing over mid-call gives up the mode and focus taken here. Taking it
     * back does not re-take them: Telecom lets go of a call only as it ends
     * (a hang-up from a headset, or leaving in the app), and grabbing focus
     * for the moment before the call stops would only interrupt whatever the
     * person was listening to. The next call starts fresh either way.
     */
    @Synchronized fun handToTelecom(handOff: TelecomRoute?) {
        if (handOff === telecom) return
        telecom = handOff
        if (!active || handOff == null) return
        if (ownsSession) releaseSession()
        route()
    }

    private fun takeSession() {
        ownsSession = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.requestAudioFocus(focus)
    }

    private fun releaseSession() {
        ownsSession = false
        audio.clearCommunicationDevice()
        audio.abandonAudioFocusRequest(focus)
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun route() {
        val available = audio.availableCommunicationDevices
        val selectedType = selectCallDeviceType(available.map { it.type }, mutableLoudspeakerOn.value) ?: return
        val selected = available.first { it.type == selectedType }
        val handOff = telecom
        if (handOff != null) { handOff.request(selected.type); return }
        if (!ownsSession) return
        if (audio.communicationDevice?.id != selected.id) audio.setCommunicationDevice(selected)
    }

    @Synchronized override fun close() {
        if (!active) return
        active = false
        audio.unregisterAudioDeviceCallback(devices)
        audio.removeOnCommunicationDeviceChangedListener(communicationDevice)
        audio.removeOnModeChangedListener(modeChanged)
        if (ownsSession) releaseSession()
    }
}
