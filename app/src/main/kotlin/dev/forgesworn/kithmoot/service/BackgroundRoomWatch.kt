package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode

/**
 * Everything [BackgroundCallListenerService] needs to open a read-only
 * subscription for one saved room: its current traffic id and key (already
 * following any rekey - see how the service resolves these from
 * `EpochVault`), the relays to ask, the identity this device answers to in
 * it (so its own other devices never ring it), and a label for the
 * notification.
 */
data class BackgroundRoomWatch(
    val stableRoomId: String,
    val roomName: String,
    val trafficRoomId: String,
    val roomKey: ByteArray,
    val relays: List<String>,
    val self: String,
)

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
