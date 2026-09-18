package dev.forgesworn.kithmoot.ui.room

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mic button's three-state cycle, checked in isolation from
 * `RoomViewModel.toggleMicrophone`, which only dispatches on this.
 *
 * Mute is deliberately never a route to [MicrophoneAction.Start] or anything
 * that would release the microphone - only [RoomViewModel.leaveCall] does
 * that. This is the whole of what stops the button regressing to the old
 * behaviour where muting shut the hardware off.
 */
class MicrophoneControlTest {

    @Test
    fun `no microphone starts one`() {
        assertEquals(MicrophoneAction.Start, microphoneAction(micOn = false, micMuted = false))
        // micMuted is stale/meaningless while there is no microphone at all,
        // and must not change the outcome.
        assertEquals(MicrophoneAction.Start, microphoneAction(micOn = false, micMuted = true))
    }

    @Test
    fun `a live unmuted microphone mutes`() {
        assertEquals(MicrophoneAction.Mute, microphoneAction(micOn = true, micMuted = false))
    }

    @Test
    fun `a live muted microphone unmutes`() {
        assertEquals(MicrophoneAction.Unmute, microphoneAction(micOn = true, micMuted = true))
    }

    @Test
    fun `cycling through all three states never asks to start over a live microphone`() {
        // Start -> (mute) -> Mute -> (unmute) -> Unmute -> (mute again) -> Mute ...
        // Once live, the action space is only ever Mute and Unmute.
        var micOn = false
        var micMuted = false
        repeat(6) {
            when (val action = microphoneAction(micOn, micMuted)) {
                MicrophoneAction.Start -> {
                    micOn = true
                    micMuted = false
                }
                MicrophoneAction.Mute -> {
                    assertEquals(true, micOn, "mute is only reachable once the microphone is live")
                    micMuted = true
                }
                MicrophoneAction.Unmute -> {
                    assertEquals(true, micOn, "unmute is only reachable once the microphone is live")
                    micMuted = false
                }
            }
            if (it > 0) assertEquals(true, micOn, "once started, the microphone never goes back to absent")
        }
    }
}
