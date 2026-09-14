package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This is the whole of how Android "recognises" the `screen-audio` role: it
 * does not. A microphone and a screen share's own sound are both just
 * incoming remote audio once WebRTC has negotiated them, and are silenced
 * by the same two rules regardless of which one they are.
 */
class RemoteAudioTest {

    @Test
    fun `a screen-audio track from another device plays exactly like a mic track would`() {
        assertTrue(shouldPlayRemoteAudio(device = "their-laptop", myDevices = emptySet(), listeningHere = true))
    }

    @Test
    fun `a track from one of this participant's own other devices is silenced`() {
        assertFalse(shouldPlayRemoteAudio(device = "my-tablet", myDevices = setOf("my-tablet"), listeningHere = true))
    }

    @Test
    fun `a track from someone else's device is unaffected by your own device set`() {
        assertTrue(shouldPlayRemoteAudio(device = "their-laptop", myDevices = setOf("my-tablet", "my-phone"), listeningHere = true))
    }

    @Test
    fun `nothing plays when another of your devices holds the monitor role`() {
        assertFalse(shouldPlayRemoteAudio(device = "their-laptop", myDevices = emptySet(), listeningHere = false))
    }

    @Test
    fun `own-device silence wins even when this device is the monitor`() {
        assertFalse(shouldPlayRemoteAudio(device = "my-tablet", myDevices = setOf("my-tablet"), listeningHere = true))
    }
}
