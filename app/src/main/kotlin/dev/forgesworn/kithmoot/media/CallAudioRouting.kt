package dev.forgesworn.kithmoot.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

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

    @Synchronized fun setActive(wanted: Boolean) {
        if (wanted == active) return
        if (!wanted) { close(); return }
        active = true
        if (telecom == null) takeSession()
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        route()
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
        // Respect attached headsets. The phone speaker is the hands-free fallback.
        val selected = available.firstOrNull { it.type in HEADSETS }
            ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return
        val handOff = telecom
        if (handOff != null) { handOff.request(selected.type); return }
        if (!ownsSession) return
        if (audio.communicationDevice?.id != selected.id) audio.setCommunicationDevice(selected)
    }

    @Synchronized override fun close() {
        if (!active) return
        active = false
        audio.unregisterAudioDeviceCallback(devices)
        if (ownsSession) releaseSession()
    }

    private companion object {
        val HEADSETS = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
    }
}
