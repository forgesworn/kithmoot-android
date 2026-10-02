package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundListeningTest {
    private class FakeRingHost(override var ringOn: Boolean, override var deliveryOn: Boolean) : RingActionHost {
        val calls = mutableListOf<String>()
        override fun startService() { calls += "start" }
        override fun stopService() { calls += "stop" }
        override fun offerTurnOn() { calls += "offer" }
        override fun clearOffer() { calls += "clear" }
    }

    @Test fun `stop ringing turns ringing off, leaves messages alone and offers the way back`() {
        val host = FakeRingHost(ringOn = true, deliveryOn = true)
        handleRingAction(BackgroundRingActionReceiver.ACTION_TURN_OFF, host)
        assertFalse(host.ringOn)
        assertTrue(host.deliveryOn, "message delivery is no longer switched off from under the person")
        assertEquals(listOf("offer"), host.calls, "the service keeps running for the messages")
    }

    @Test fun `stop ringing with nothing else to do stops the service`() {
        val host = FakeRingHost(ringOn = true, deliveryOn = false)
        handleRingAction(BackgroundRingActionReceiver.ACTION_TURN_OFF, host)
        assertEquals(listOf("stop", "offer"), host.calls)
    }

    @Test fun `stop ringing when ringing was already off offers nothing`() {
        val host = FakeRingHost(ringOn = false, deliveryOn = true)
        handleRingAction(BackgroundRingActionReceiver.ACTION_TURN_OFF, host)
        assertEquals(emptyList(), host.calls)
    }

    @Test fun `turn back on restores ringing, starts the service and clears the offer`() {
        val host = FakeRingHost(ringOn = false, deliveryOn = false)
        handleRingAction(BackgroundRingActionReceiver.ACTION_TURN_ON, host)
        assertTrue(host.ringOn)
        assertFalse(host.deliveryOn, "what the person chose for messages is not changed")
        assertEquals(listOf("start", "clear"), host.calls)
    }

    @Test fun `another action does nothing`() {
        val host = FakeRingHost(ringOn = true, deliveryOn = true)
        handleRingAction("something.else", host)
        handleRingAction(null, host)
        assertTrue(host.ringOn)
        assertEquals(emptyList(), host.calls)
    }

    @Test fun `the action and the follow-up say what they do`() {
        assertEquals("Stop ringing", STOP_RINGING_LABEL)
        val words = ringingOffNoticeText()
        assertEquals("Calls won't ring while KithMoot is closed", words.title)
        assertEquals("You stopped ringing from the notification. Turn it back on to hear calls when KithMoot is closed.", words.text)
    }

    @Test fun `the banner offers one press to turn ringing on`() {
        val banner = ringingOffBanner()
        assertEquals("Calls won't ring while KithMoot is closed.", banner.message)
        assertEquals("Ringing in the background is turned off.", banner.detail)
        assertEquals("Turn on", banner.action)
    }

    private fun room(id: String, mode: CallRingMode = CallRingMode.RING, excluded: Boolean = false) =
        RenewalCandidate(id, true, "a".repeat(64), excluded, CallRingMode.NOTHING, null, chosenMode = mode)

    @Test fun `ringing is said to be off only while a room is set to Ring me`() {
        assertTrue(ringingSwitchedOff(false, listOf(room("a"))))
        assertFalse(ringingSwitchedOff(true, listOf(room("a"))), "on is on")
        assertFalse(ringingSwitchedOff(false, emptyList()), "no rooms, nothing to ring for")
        assertFalse(ringingSwitchedOff(false, listOf(room("a", CallRingMode.QUIET), room("b", CallRingMode.NOTHING))))
        assertFalse(ringingSwitchedOff(false, listOf(room("a", excluded = true))), "an ended room does not count")
        assertTrue(ringingSwitchedOff(false, listOf(room("a", CallRingMode.QUIET), room("b"))))
    }

    @Test fun `the service starts on a reboot, an update, or notifications unblocked, and on nothing else`() {
        assertTrue(startsBackgroundService("android.intent.action.BOOT_COMPLETED", blocked = true))
        assertTrue(startsBackgroundService("android.intent.action.MY_PACKAGE_REPLACED", blocked = true))
        assertTrue(startsBackgroundService("android.app.action.APP_BLOCK_STATE_CHANGED", blocked = false))
        assertFalse(startsBackgroundService("android.app.action.APP_BLOCK_STATE_CHANGED", blocked = true), "notifications blocked: nothing to start")
        assertFalse(startsBackgroundService("android.intent.action.SCREEN_ON", blocked = false))
        assertFalse(startsBackgroundService(null, blocked = false))
    }
}
