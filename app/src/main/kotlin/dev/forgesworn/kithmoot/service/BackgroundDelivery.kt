package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.HybridRelaySockets
import dev.forgesworn.kithmoot.relay.LinkRelaySocketFactory
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.CHAT_RETENTION_SECONDS
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.PendingChatOutbox

/**
 * The pure rules behind background message delivery (see
 * [BackgroundCallListenerService]), kept Android-free so every decision is
 * unit-testable. See the P4-01 delivery ticket for the mechanism.
 */

/** What the person is shown about background delivery. */
enum class DeliveryState(val label: String) {
    OFF("Off"),
    LIVE("Receiving"),
    RECONNECTING("Reconnecting"),
    WAITING_FOR_NETWORK("Waiting for network"),
    NEEDS_SIGNER("Needs your signer"),
    RESTRICTED("Restricted by Android"),
    /** The last run ended without the service saying so: force-stop or a crash. */
    STOPPED("Stopped. Catching up now"),
}

/** Why a saved room is not watched for messages in the background. */
enum class DeliveryExclusion(val reason: String) {
    ANONYMOUS("Anonymous rooms use Tor only and receive when opened."),
    QUIET("Quiet rooms send on their own schedule and receive when opened."),
    ENDED("This room has ended."),
    OPEN("Open in KithMoot."),
    NO_EPOCH("You are no longer in this room."),
    BUNKER("A bunker account cannot sign in the background. Open KithMoot."),
}

data class DeliveryCandidate(
    val roomId: String,
    val anonymous: Boolean,
    val quiet: Boolean,
    val ended: Boolean,
    val epochId: String?,
    val needsBunker: Boolean = false,
)

/** Checked in this order, so the most fundamental reason is the one shown. */
fun deliveryExclusion(candidate: DeliveryCandidate, isOpenInApp: (String) -> Boolean): DeliveryExclusion? = when {
    candidate.anonymous -> DeliveryExclusion.ANONYMOUS
    candidate.ended -> DeliveryExclusion.ENDED
    candidate.epochId == null -> DeliveryExclusion.NO_EPOCH
    candidate.quiet -> DeliveryExclusion.QUIET
    candidate.needsBunker -> DeliveryExclusion.BUNKER
    isOpenInApp(candidate.roomId) -> DeliveryExclusion.OPEN
    else -> null
}

/** Allowance for a sender's clock and a relay's ordering when resuming from the cursor. */
const val CHAT_CURSOR_SKEW_SECONDS: Long = 300

/** Resume from the cursor, but never ask for more than the retention window. */
fun chatSince(cursor: Long, now: Long): Long = maxOf(cursor - CHAT_CURSOR_SKEW_SECONDS, now - CHAT_RETENTION_SECONDS)

fun backgroundChatFilter(epochId: String, cursor: Long, now: Long) = Filter(
    kinds = listOf(KIND_CHAT),
    // "#d", not "d": Filter's wire form for a tag filter (see relay/Filter.kt).
    tags = mapOf("#d" to listOf(epochId)),
    since = chatSince(cursor, now),
)

/**
 * The background pool's sockets: the same hybrid factory an open room uses,
 * so a Link-shaped relay address without active consent fails closed and can
 * never reach OkHttp or DNS.
 */
fun backgroundSockets(publicSockets: RelaySocketFactory, linkSockets: LinkRelaySocketFactory, route: ActiveLinkRoute): RelaySocketFactory =
    HybridRelaySockets(publicSockets, linkSockets, route)

/** Whether the service has any work: ringing, message delivery, or both. */
fun shouldRunBackgroundService(
    ringEnabled: Boolean,
    deliveryEnabled: Boolean,
    savedRoomIds: List<String>,
    ringMode: (roomId: String) -> CallRingMode,
    notificationsPermitted: Boolean = true,
): Boolean = notificationsPermitted && savedRoomIds.isNotEmpty() &&
    (deliveryEnabled || shouldRunBackgroundListener(ringEnabled, savedRoomIds, ringMode, notificationsPermitted))

/** One watched room's connection, as the state summary sees it. */
data class RoomLink(val relaysUp: Int, val needsSigner: Boolean)

/** One room: a relay that is up is receiving, whatever else is true;
 *  otherwise the most fundamental reason. */
fun roomDeliveryState(room: RoomLink, network: Boolean, restricted: Boolean): DeliveryState = when {
    room.relaysUp > 0 -> DeliveryState.LIVE
    restricted -> DeliveryState.RESTRICTED
    !network -> DeliveryState.WAITING_FOR_NETWORK
    room.needsSigner -> DeliveryState.NEEDS_SIGNER
    else -> DeliveryState.RECONNECTING
}

/** Every room, summarised by the one in the worst state, so one connected
 *  room cannot hide another that is stuck. */
fun deriveDeliveryState(rooms: List<RoomLink>, network: Boolean, restricted: Boolean): DeliveryState =
    rooms.map { roomDeliveryState(it, network, restricted) }.maxByOrNull { SEVERITY.indexOf(it) } ?: DeliveryState.OFF

private val SEVERITY = listOf(DeliveryState.LIVE, DeliveryState.RECONNECTING, DeliveryState.NEEDS_SIGNER,
    DeliveryState.WAITING_FOR_NETWORK, DeliveryState.RESTRICTED)

enum class FlushOutcome { NOTHING, SENT, NOT_CONFIRMED, EPOCH_CHANGED }

/**
 * Sends the exact retained message once a relay is back. Only on the epoch it
 * was sealed for; a changed epoch leaves it for the open room to explain.
 * Cleared only when a relay confirms that event id.
 */
suspend fun flushPending(outbox: PendingChatOutbox, activeEpochId: String, transport: RoomTransport,
    timeoutMs: Long = 15_000): FlushOutcome {
    val pending = outbox.pending() ?: return FlushOutcome.NOTHING
    if (pending.epochId != activeEpochId) return FlushOutcome.EPOCH_CHANGED
    if (!transport.publishConfirmed(pending.event, timeoutMs)) return FlushOutcome.NOT_CONFIRMED
    outbox.confirm(pending.event.id)
    return FlushOutcome.SENT
}
