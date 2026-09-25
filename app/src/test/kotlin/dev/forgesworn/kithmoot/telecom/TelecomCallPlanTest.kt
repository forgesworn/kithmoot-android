package dev.forgesworn.kithmoot.telecom

import android.media.AudioDeviceInfo
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelecomCallPlanTest {
    @Test
    fun `a permitted ring goes through Telecom`() {
        assertEquals(RingPath.TELECOM, ringPath(quiet = false, telecomOn = true, incomingPermitted = true))
    }

    @Test
    fun `the kill switch rings exactly as before`() {
        assertEquals(RingPath.NOTIFICATION, ringPath(quiet = false, telecomOn = false, incomingPermitted = true))
        assertEquals(RingPath.NOTIFICATION, ringPath(quiet = false, telecomOn = false, incomingPermitted = null))
    }

    @Test
    fun `Telecom throwing rings exactly as before`() {
        assertEquals(RingPath.NOTIFICATION, ringPath(quiet = false, telecomOn = true, incomingPermitted = null))
    }

    @Test
    fun `Telecom refusing, as during an emergency call, shows the call without ringing over it`() {
        assertEquals(RingPath.QUIET_NOTIFICATION, ringPath(quiet = false, telecomOn = true, incomingPermitted = false))
        assertEquals(RingPath.QUIET_NOTIFICATION, FAILED_INCOMING_PATH)
    }

    @Test
    fun `a quiet room never involves Telecom`() {
        for (on in listOf(true, false)) for (permitted in listOf(true, false, null)) {
            assertEquals(RingPath.QUIET_NOTIFICATION, ringPath(quiet = true, telecomOn = on, incomingPermitted = permitted))
        }
    }

    @Test
    fun `joining adopts an answered call before placing a new one`() {
        var asked = false
        assertEquals(JoinStep.ADOPT_ANSWERED, joinStep(hasLive = false, hasAnswered = true, telecomOn = true) { asked = true; true })
        assertFalse("an answered call needs no permission check", asked)
        // Answered while the switch was on; turning it off mid-ring still lets that call finish.
        assertEquals(JoinStep.ADOPT_ANSWERED, joinStep(hasLive = false, hasAnswered = true, telecomOn = false) { true })
    }

    @Test
    fun `joining places an outgoing call only when switched on and permitted`() {
        assertEquals(JoinStep.PLACE, joinStep(hasLive = false, hasAnswered = false, telecomOn = true) { true })
        assertEquals(JoinStep.NOTHING, joinStep(hasLive = false, hasAnswered = false, telecomOn = true) { false })
        assertEquals(JoinStep.NOTHING, joinStep(hasLive = false, hasAnswered = false, telecomOn = true) { null })
        var asked = false
        assertEquals(JoinStep.NOTHING, joinStep(hasLive = false, hasAnswered = false, telecomOn = false) { asked = true; true })
        assertFalse("the kill switch keeps Telecom out entirely", asked)
    }

    @Test
    fun `one Telecom call per live call`() {
        assertEquals(JoinStep.NOTHING, joinStep(hasLive = true, hasAnswered = true, telecomOn = true) { true })
        assertEquals(JoinStep.NOTHING, joinStep(hasLive = true, hasAnswered = false, telecomOn = true) { true })
    }

    @Test
    fun `a hang-up while ringing declines, and after answering leaves`() {
        assertEquals(HangUpStep.DECLINE, hangUpStep(TelecomPhase.RINGING))
        assertEquals(HangUpStep.LEAVE, hangUpStep(TelecomPhase.ANSWERED))
        assertEquals(HangUpStep.LEAVE, hangUpStep(TelecomPhase.ACTIVE))
        assertEquals(HangUpStep.LEAVE, hangUpStep(TelecomPhase.HELD))
    }

    @Test
    fun `end reasons map to Telecom disconnect causes`() {
        assertEquals(DisconnectCause.REJECTED, disconnectCode(EndReason.DECLINED))
        assertEquals(DisconnectCause.MISSED, disconnectCode(EndReason.MISSED))
        assertEquals(DisconnectCause.LOCAL, disconnectCode(EndReason.LOCAL))
        assertEquals(DisconnectCause.CANCELED, disconnectCode(EndReason.CANCELLED))
    }

    @Test
    fun `communication devices map to Telecom routes`() {
        assertEquals(CallAudioState.ROUTE_WIRED_HEADSET, telecomRouteFor(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(CallAudioState.ROUTE_WIRED_HEADSET, telecomRouteFor(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(CallAudioState.ROUTE_BLUETOOTH, telecomRouteFor(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(CallAudioState.ROUTE_BLUETOOTH, telecomRouteFor(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(CallAudioState.ROUTE_BLUETOOTH, telecomRouteFor(AudioDeviceInfo.TYPE_HEARING_AID))
        assertEquals(CallAudioState.ROUTE_SPEAKER, telecomRouteFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertNull(telecomRouteFor(AudioDeviceInfo.TYPE_HDMI))
    }

    @Test
    fun `Telecom landing on the earpiece is steered back to the wanted route`() {
        val all = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER or CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET
        assertEquals(CallAudioState.ROUTE_SPEAKER, routeCorrection(CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_SPEAKER, all))
        // Not the earpiece, nothing asked for, or the wanted route gone: leave it.
        assertNull(routeCorrection(CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_SPEAKER, all))
        assertNull(routeCorrection(CallAudioState.ROUTE_EARPIECE, null, all))
        assertNull(routeCorrection(CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_BLUETOOTH,
            CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER))
    }

    @Test
    fun `hold mutes only a live unmuted microphone`() {
        assertTrue(holdMutesMic(micOn = true, micMuted = false))
        assertFalse(holdMutesMic(micOn = true, micMuted = true))
        assertFalse(holdMutesMic(micOn = false, micMuted = false))
    }
}
