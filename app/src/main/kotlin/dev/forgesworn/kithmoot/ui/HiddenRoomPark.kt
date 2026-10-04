package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.service.DeliveryCandidate
import dev.forgesworn.kithmoot.service.deliveryExclusion

/**
 * How long a room must sit on screen behind other apps, with nothing going on
 * in it, before it is handed to the closed-app listener. Long enough that a
 * signer prompt, a file picker or a quick look at another app comes back to
 * the room still open.
 */
internal const val PARK_HIDDEN_ROOM_AFTER_MS = 5L * 60 * 1000

/** How often a hidden room is looked at again while it waits to be parked. */
internal const val PARK_CHECK_INTERVAL_MS = 30_000L

/**
 * How long a return waits before reopening a parked room, so a notification
 * tap or a link that arrives with the return opens what it names instead.
 */
internal const val RESUME_PARKED_AFTER_MS = 300L

/** Everything about an open, hidden room that decides whether it may be parked. */
internal data class ParkCheck(
    /** The room this device would watch in the background, as the service sees it. */
    val delivery: DeliveryCandidate?,
    /** The closed-app listener is running and receiving messages. */
    val listenerReceiving: Boolean,
    /** Opened beside a call in another room. */
    val chatOnly: Boolean,
    val onCall: Boolean,
    val callJoinPending: Boolean,
    val callChanging: Boolean,
    val mediaStarting: Boolean,
    val screenOn: Boolean,
    val recording: Boolean,
    /** An entry or a leave, a send, a room update or other piece of work is in flight. */
    val busy: Boolean,
)

/**
 * Whether a room left open behind other apps should be closed and handed to
 * the closed-app listener.
 *
 * An open room restates its presence every 20 seconds and takes every other
 * device's heartbeat from every relay, so a phone that left one on screen
 * behind the home screen kept its radio up all day. The listener receives
 * the same room's messages and bells over far less traffic. A room is
 * parked only when that listener will actually pick it up, and never while
 * anything is happening in it: a call, a share, a recording or a send.
 */
internal fun shouldParkHiddenRoom(check: ParkCheck): Boolean {
    val delivery = check.delivery ?: return false
    if (!check.listenerReceiving || check.chatOnly || check.busy) return false
    if (check.onCall || check.callJoinPending || check.callChanging || check.mediaStarting) return false
    if (check.screenOn || check.recording) return false
    // The room is open here, which the listener would otherwise name as its reason.
    return deliveryExclusion(delivery) { false } == null
}
