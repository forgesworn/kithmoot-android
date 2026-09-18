package dev.forgesworn.kithmoot.ui.room

/**
 * Whether this device counts as "on a call" for comfort features such as
 * keeping the screen on. Matches the call tab's own "Call - live" label:
 * any of mic, camera or screen share being on means a call is under way,
 * whichever tab happens to be showing.
 */
fun isOnCall(micOn: Boolean, cameraOn: Boolean, screenOn: Boolean): Boolean =
    micOn || cameraOn || screenOn

/**
 * Whether this device is in a call, for the two things that need to know: the
 * ongoing notification, and keeping the screen awake.
 *
 * "Membership, or live local media." Not the media engine running, which is
 * true from the moment any room opens - a person reading a quiet room for
 * half an hour was holding their screen awake and showing an in-call
 * notification because the engine existed. Not membership alone either: a
 * device sending a camera has a call going on whatever it has declared, and
 * the declaration follows a moment later anyway.
 *
 * @param onCall this device has declared call membership on the roster.
 * @param mediaRunning the media stack is engaged here at all.
 * @param connected some peer connection is actually carrying something.
 */
fun inACall(
    onCall: Boolean,
    mediaRunning: Boolean,
    micOn: Boolean,
    cameraOn: Boolean,
    screenOn: Boolean,
    connected: Boolean = false,
): Boolean = onCall || (mediaRunning && (isOnCall(micOn, cameraOn, screenOn) || connected))
