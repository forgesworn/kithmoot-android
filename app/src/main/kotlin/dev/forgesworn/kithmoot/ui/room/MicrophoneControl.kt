package dev.forgesworn.kithmoot.ui.room

/**
 * What the mic button does next, given where the microphone currently stands.
 *
 * One button, three states, matching the web client: no microphone starts
 * one; a live microphone mutes without releasing it; a live, muted
 * microphone unmutes. Leaving the call is the only thing that releases the
 * microphone outright - see `RoomViewModel.leaveCall` - muting never does.
 */
sealed interface MicrophoneAction {
    /** No microphone yet here: claim the role and start capture. */
    data object Start : MicrophoneAction

    /** A live, unmuted microphone: silence it at the source, keep it running. */
    data object Mute : MicrophoneAction

    /** A live, muted microphone: let the room hear it again. */
    data object Unmute : MicrophoneAction
}

/**
 * Pure decision behind `RoomViewModel.toggleMicrophone`, kept separate so the
 * three-state cycle can be checked without an Android runtime or a real
 * microphone. `micMuted` is meaningless while `micOn` is false, and ignored.
 */
fun microphoneAction(micOn: Boolean, micMuted: Boolean): MicrophoneAction = when {
    !micOn -> MicrophoneAction.Start
    !micMuted -> MicrophoneAction.Mute
    else -> MicrophoneAction.Unmute
}
