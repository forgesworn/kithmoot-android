package dev.forgesworn.kithmoot.media

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CallAudioRoutingTest {
    @Test
    fun `Telecom speaker request with earpiece still selected repairs the app route without taking focus`() {
        var output = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val requests = mutableListOf<String>()
        val placed = requestCallOutput(
            telecomRequest = { requests += "telecom speaker" }, ownsSession = false,
            alreadySelected = { output == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
            requestDevice = { requests += "app speaker"; output = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER; true },
            allowTelecomDeviceRequest = true,
        )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, output)
        assertEquals(listOf("telecom speaker", "app speaker"), requests)
        assertTrue(placed, "The app must clear its fallback device request on leave")
    }

    @Test
    fun `a matching Telecom output needs no app device request`() {
        var requests = 0
        assertFalse(requestCallOutput(telecomRequest = { requests++ }, ownsSession = false,
            alreadySelected = { true }, requestDevice = { error("Already routed") }))
        assertEquals(1, requests)
    }

    @Test
    fun `an inactive session cannot take over a device`() {
        assertFalse(requestCallOutput(telecomRequest = null, ownsSession = false,
            alreadySelected = { false }, requestDevice = { error("No call owns audio") }))
    }

    @Test
    fun `a refused device request is not recorded as an owned route`() {
        var requests = 0
        assertFalse(requestCallOutput(telecomRequest = { requests++ }, ownsSession = false,
            alreadySelected = { false }, requestDevice = { requests++; false }, allowTelecomDeviceRequest = true))
        assertEquals(2, requests)
    }

    @Test
    fun `a call without Telecom requests its own device`() {
        var requests = 0
        assertTrue(requestCallOutput(telecomRequest = null, ownsSession = true,
            alreadySelected = { false }, requestDevice = { requests++; true }))
        assertEquals(1, requests)
    }

    @Test
    fun `a held or stale Telecom call cannot take over another calls output`() {
        assertFalse(requestCallOutput(telecomRequest = { }, ownsSession = false,
            alreadySelected = { false }, requestDevice = { error("Held call cannot change output") },
            allowTelecomDeviceRequest = false))
    }

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
