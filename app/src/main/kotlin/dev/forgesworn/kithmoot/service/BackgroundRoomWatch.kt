package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.protocol.CALL_BELL_FUTURE_SKEW_SECONDS
import dev.forgesworn.kithmoot.protocol.CALL_BELL_TTL_SECONDS
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
 * turned it on, and at least one saved room actually wants Ring me. Turning
 * off the last Ring me room stops the service the same as turning the
 * switch off directly - see [BackgroundCallListenerService].
 */
fun shouldRunBackgroundListener(
    toggleEnabled: Boolean,
    savedRoomIds: List<String>,
    ringMode: (roomId: String) -> CallRingMode,
): Boolean = toggleEnabled && savedRoomIds.any { ringMode(it) == CallRingMode.RING }

/**
 * The relay URLs a set of watches needs subscribed, deduplicated: one
 * connection per relay serves every room that lists it, rather than one
 * `RelayPool` per room. Order is stable so a redundant reconnect never
 * results from watch-list churn alone.
 */
fun sharedRelayUrls(watches: List<BackgroundRoomWatch>): List<String> =
    watches.flatMap { it.relays }.distinct()
