package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.protocol.CALL_BELL_FUTURE_SKEW_SECONDS
import dev.forgesworn.kithmoot.protocol.CALL_BELL_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.CallBell
import dev.forgesworn.kithmoot.protocol.CallBellState
import dev.forgesworn.kithmoot.protocol.callBellTag

/**
 * Everything [BackgroundCallListenerService] needs to watch one saved room
 * for a call bell (kind 1464, `protocol/CallBell.kt`): the room's own
 * (epoch-0) id, which the bell's device signature is bound to and which
 * names it in the UI; its current epoch key, which derives the day tags it
 * rings under and the key its content decrypts with - already following
 * any rekey, the same durable journal `RoomViewModel` reads; the relays to
 * ask; this device's own participant and device pubkey, so a bell rung by
 * one of this device's own other devices is never mistaken for an incoming
 * call; and the credential-checked participant this call bell path never
 * carries on the wire, which a `BackgroundParticipantCache` hit resolves
 * for display when it can.
 */
data class BackgroundRoomWatch(
    val stableRoomId: String,
    val roomName: String,
    val bellKey: ByteArray,
    val relays: List<String>,
    val selfParticipant: String,
    val selfDevice: String,
)

private const val ONE_DAY_SECONDS = 86_400L

/** The one, two or three day tags this room's bell could carry right now:
 *  today's, and the neighbouring day's while a bell stamped on the other
 *  side of UTC midnight could still be accepted. Mirrors
 *  `callBellListenTags` in `protocol/CallBell.kt`, but named per room since
 *  a room's tag depends on its own key. */
fun callBellTagsFor(watch: BackgroundRoomWatch, now: Long): Set<String> =
    setOf(now - CALL_BELL_TTL_SECONDS, now, now + CALL_BELL_FUTURE_SKEW_SECONDS, now - ONE_DAY_SECONDS, now + ONE_DAY_SECONDS)
        .map { callBellTag(watch.bellKey, it) }
        .toSet()

/** Every day tag every watched room could ring under right now: the `#d`
 *  values for the shared subscription's filter. */
fun callBellFilterTags(watches: List<BackgroundRoomWatch>, now: Long): List<String> =
    watches.flatMap { callBellTagsFor(it, now) }.distinct()

/**
 * The "Ring when KithMoot is closed" default-on migration, in its own pure
 * function so it is unit-testable without a real `Context` -
 * [BackgroundRingSettings.enabled] is the only caller. An install that has
 * never stored a value for the switch is migrated to `true` and that value
 * is written back, so the stored state and what Settings shows always
 * agree from the first read on. An install that explicitly chose a value,
 * on or off, keeps it untouched.
 */
fun migrateBackgroundRingEnabled(hasStoredValue: Boolean, storedValue: Boolean, write: (Boolean) -> Unit): Boolean {
    if (hasStoredValue) return storedValue
    write(true)
    return true
}

/** What a decoded, verified bell should do to the notification for its
 *  room. Pure - [BackgroundCallListenerService.onBell] is the only caller
 *  that has a `Context` to act on it with. */
sealed interface BellOutcome {
    /** Ring, or update, the incoming-call notification for [watch]. */
    data class Ring(val watch: BackgroundRoomWatch, val callId: String, val caller: String) : BellOutcome
    /** Stop any ring for [watch]: the call ended. */
    data class Stop(val watch: BackgroundRoomWatch) : BellOutcome
    /** This device's own other device rang it - never shown. */
    data object Ignore : BellOutcome
}

/**
 * The whole decision a bell makes for one room, once decoded and verified:
 * never for this device's own other devices' bells (compared by device
 * key, not participant, so it also catches a device this cache has never
 * resolved a participant for); a `start` rings with the best caller label
 * available; an `end` stops it. [participant] is a
 * [BackgroundParticipantCache] hit for [CallBell.device], or null when this
 * phone has never seen that device live in this room.
 */
fun outcomeFor(bell: CallBell, watch: BackgroundRoomWatch, participant: String?): BellOutcome {
    if (bell.device == watch.selfDevice) return BellOutcome.Ignore
    return when (bell.state) {
        CallBellState.START -> BellOutcome.Ring(watch, bell.call.id, participant ?: "Someone in ${watch.roomName}")
        CallBellState.END -> BellOutcome.Stop(watch)
    }
}

/**
 * Which saved rooms the background listener should actually watch: only
 * those set to Ring me, and only while no `RoomViewModel` in this process
 * already has that room open - see `notifications/ActiveRoomRegistry.kt`.
 * Watching a room already open in-app would ring it twice for one call.
 *
 * Pure and Android-free so the decision is unit-testable.
 */
fun roomsToWatch(
    candidates: List<BackgroundRoomWatch>,
    ringMode: (roomId: String) -> CallRingMode,
    isOpenInApp: (roomId: String) -> Boolean,
): List<BackgroundRoomWatch> =
    candidates.filter { ringMode(it.stableRoomId) == CallRingMode.RING && !isOpenInApp(it.stableRoomId) }

/**
 * Whether the background listener should be running at all: the person has
 * the switch on (on by default - see [BackgroundRingSettings]), at least one
 * saved room actually wants Ring me (also the default, per room), and
 * notifications are actually permitted - a service that cannot post the
 * "Listening for calls" notification cannot legally run as a foreground
 * service on modern Android, and ringing silently with no notification at
 * all would be worse. Turning off the last Ring me room, or notifications
 * being withdrawn, stops the service the same as turning the switch off
 * directly - see [BackgroundCallListenerService].
 */
fun shouldRunBackgroundListener(
    toggleEnabled: Boolean,
    savedRoomIds: List<String>,
    ringMode: (roomId: String) -> CallRingMode,
    notificationsPermitted: Boolean = true,
): Boolean = toggleEnabled && notificationsPermitted && savedRoomIds.any { ringMode(it) == CallRingMode.RING }

/**
 * The relay URLs a set of watches needs subscribed, deduplicated: one
 * connection per relay serves every room that lists it, rather than one
 * `RelayPool` per room. Order is stable so a redundant reconnect never
 * results from watch-list churn alone.
 */
fun sharedRelayUrls(watches: List<BackgroundRoomWatch>): List<String> =
    watches.flatMap { it.relays }.distinct()
