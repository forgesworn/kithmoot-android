package dev.forgesworn.kithmoot.ui.room

/**
 * Whether this device counts as "on a call" for comfort features such as
 * keeping the screen on. Matches the call tab's own "Call - live" label:
 * any of mic, camera or screen share being on means a call is under way,
 * whichever tab happens to be showing.
 */
fun isOnCall(micOn: Boolean, cameraOn: Boolean, screenOn: Boolean): Boolean =
    micOn || cameraOn || screenOn
