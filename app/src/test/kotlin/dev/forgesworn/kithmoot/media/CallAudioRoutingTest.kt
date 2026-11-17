package dev.forgesworn.kithmoot.media

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class CallAudioRoutingTest {
    @Test
    fun `loudspeaker on selects the built-in speaker`() {
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            selectCallDeviceType(BUILT_IN_DEVICES, loudspeakerOn = true),
        )
    }

    @Test
    fun `loudspeaker off selects the earpiece`() {
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            selectCallDeviceType(BUILT_IN_DEVICES, loudspeakerOn = false),
        )
    }

    @Test
    fun `loudspeaker off uses a connected headset`() {
        assertEquals(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            selectCallDeviceType(BUILT_IN_DEVICES + AudioDeviceInfo.TYPE_USB_HEADSET, loudspeakerOn = false),
        )
    }

    @Test
    fun `an explicit loudspeaker choice overrides connected headsets`() {
        for (headset in listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)) {
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                selectCallDeviceType(BUILT_IN_DEVICES + headset, loudspeakerOn = true))
        }
    }

    @Test
    fun `a call still defaults to its connected headset before an explicit choice`() {
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET,
            selectCallDeviceType(BUILT_IN_DEVICES + AudioDeviceInfo.TYPE_USB_HEADSET, loudspeakerOn = true, preferHeadset = true))
    }

    @Test
    fun `loudspeaker off falls back to speaker when there is no earpiece`() {
        assertEquals(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            selectCallDeviceType(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), loudspeakerOn = false),
        )
    }

    private companion object {
        val BUILT_IN_DEVICES = setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }
}
