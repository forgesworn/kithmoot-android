package dev.forgesworn.kithmoot.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Own communication routing only while this device is sending or receiving call audio. */
class CallAudioRouting(context: Context) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var active = false
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
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.requestAudioFocus(focus)
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        route()
    }

    private fun route() {
        val available = audio.availableCommunicationDevices
        // Respect attached headsets. The phone speaker is the hands-free fallback.
        val selected = available.firstOrNull { it.type in HEADSETS }
            ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (selected != null && audio.communicationDevice?.id != selected.id) audio.setCommunicationDevice(selected)
    }

    @Synchronized override fun close() {
        if (!active) return
        active = false
        audio.unregisterAudioDeviceCallback(devices)
        audio.clearCommunicationDevice()
        audio.abandonAudioFocusRequest(focus)
        audio.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        val HEADSETS = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
    }
}
