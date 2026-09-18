package dev.forgesworn.kithmoot.ui.room

/**
 * What pressing Join means, given what this device actually has to join with.
 *
 * Before this there were two answers and both were wrong in the same way. A
 * press with no engine behind it said "Audio and video are still starting.
 * Try again shortly." and threw the press away; a press while a leave was
 * still settling returned without a word. Neither remembered that somebody
 * had asked to be on the call, so the room that opened at a non-active epoch
 * could be pressed at for as long as the person had patience.
 */
sealed interface JoinDecision {
    /** Already on the call, or a leave has not settled: nothing to do. */
    data object Ignore : JoinDecision

    /** Audio and video exist here. Go on the call. */
    data object Now : JoinDecision

    /** Nothing to join with yet and it is on its way. Remember the press and
     *  carry it out when the engine arrives. */
    data object WhenReady : JoinDecision

    /** Nothing to join with, and nothing coming. Say why. */
    data class Refuse(val message: String) : JoinDecision
}

/**
 * Pure decision behind `RoomViewModel.joinCall`.
 *
 * `mediaReady` is "an engine exists here", `mediaStarting` is "one is being
 * built, or the room is waiting for its epoch before building one", and
 * `mediaFault` is the engine having failed for good.
 */
fun joinDecision(
    onCall: Boolean,
    changing: Boolean,
    mediaReady: Boolean,
    mediaStarting: Boolean,
    mediaFault: String?,
): JoinDecision = when {
    changing || onCall -> JoinDecision.Ignore
    mediaReady -> JoinDecision.Now
    mediaFault != null -> JoinDecision.Refuse(mediaFault)
    mediaStarting -> JoinDecision.WhenReady
    else -> JoinDecision.Refuse("This device has no audio or video in this room.")
}

/** The line beside the call control while a press is waiting on the engine. */
const val JOIN_PENDING_LABEL = "Joining when audio and video are ready…"
