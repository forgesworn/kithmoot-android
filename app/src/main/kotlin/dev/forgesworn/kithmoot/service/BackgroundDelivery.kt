package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.HybridRelaySockets
import dev.forgesworn.kithmoot.relay.LinkRelaySocketFactory
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.CHAT_RETENTION_SECONDS
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.PastEpoch
import dev.forgesworn.kithmoot.session.decodeChatEvent
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.PendingChatOutbox
import dev.forgesworn.kithmoot.session.PendingChatState
import dev.forgesworn.kithmoot.session.SENDER_CLOCK_ALLOWANCE_SECONDS

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

/**
 * Allowance for a sender's clock and a relay's ordering when resuming from the
 * cursor. At least [SENDER_CLOCK_ALLOWANCE_SECONDS], or the relay would hold
 * back a message the inbox would count.
 */
const val CHAT_CURSOR_SKEW_SECONDS: Long = SENDER_CLOCK_ALLOWANCE_SECONDS

/** Resume from the cursor, but never ask for more than the retention window. */
fun chatSince(cursor: Long, now: Long): Long = maxOf(cursor - CHAT_CURSOR_SKEW_SECONDS, now - CHAT_RETENTION_SECONDS)

/** Chat on the current epoch and on [pastIds], the epochs left that are still read (`pastEpochsFor`). */
fun backgroundChatFilter(epochId: String, cursor: Long, now: Long, pastIds: List<String> = emptyList()) = Filter(
    kinds = listOf(KIND_CHAT),
    // "#d", not "d": Filter's wire form for a tag filter (see relay/Filter.kt).
    tags = mapOf("#d" to listOf(epochId) + pastIds),
    since = chatSince(cursor, now),
)

/**
 * A background chat event, opened under the epoch its `d` tag names: the
 * current one, or one the room has left that is still read. A message from
 * somebody a rekey removed is refused on a left epoch, as the open room
 * refuses it (`RoomSession.onChatEvent`); on the current epoch they hold no
 * key to have written it.
 */
fun decodeBackgroundChat(
    event: NostrEvent,
    current: EpochKeys,
    past: List<PastEpoch>,
    removed: Set<String>,
    now: Long,
    policy: RoomPolicy?,
    credentialRoomId: String,
): ChatMessage? {
    val tag = event.tagValue("d") ?: return null
    val (keys, left) = when {
        tag.equals(current.id, ignoreCase = true) -> current to false
        else -> past.firstOrNull { it.keys.id.equals(tag, ignoreCase = true) }?.let { it.keys to true } ?: return null
    }
    val message = decodeChatEvent(event, keys.id, keys.key, now, policy, credentialRoomId = credentialRoomId) ?: return null
    if (left && message.participant.lowercase() in removed) return null
    return message
}

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

/** A switch shows the effect, not the wish: on only when Android would let it work. The saved choice is never rewritten. */
fun effectivelyOn(saved: Boolean, notificationsAllowed: Boolean): Boolean = saved && notificationsAllowed

/**
 * The line under "Receive messages when KithMoot is closed", or null with the
 * switch off. Says why nothing is arriving before it says how delivery is doing.
 */
fun backgroundStatusLine(switchOn: Boolean, notificationsAllowed: Boolean, savedRooms: Int, state: DeliveryState): String? = when {
    !switchOn -> null
    !notificationsAllowed -> "Paused until notifications are allowed"
    savedRooms <= 0 -> "Nothing to receive yet: you have no saved rooms"
    else -> "Now: ${state.label}"
}

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
    val items = outbox.items()
    if (items.isEmpty()) return FlushOutcome.NOTHING
    // Oldest first, and one that cannot go holds the rest, as the open room's queue does.
    var sent = false
    for (pending in items) {
        if (pending.state == PendingChatState.MOVED) continue
        if (pending.epochId != activeEpochId) return FlushOutcome.EPOCH_CHANGED
        if (!transport.publishConfirmed(pending.event, timeoutMs)) return FlushOutcome.NOT_CONFIRMED
        outbox.confirm(pending.event.id)
        sent = true
    }
    return if (sent) FlushOutcome.SENT else FlushOutcome.NOTHING
}
