package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.protocol.laneOfRelays

/** What a Tor-only room enforces, in the words the room's details already use. */
internal const val TOR_ONLY_MEANING = "This room connects only through Orbot to v3 onion relays."

/**
 * The line above a room's messages. A Tor-only room's onion relays are public
 * by the lane's rule, as each message's chip says, so the line names the lane
 * and then the carrier rather than leaving the lane unknown.
 */
internal fun privacyLine(lane: Lane?, torOnly: Boolean, quiet: Boolean): String =
    "Encrypted" + when (lane) {
        Lane.PUBLIC -> " · public relays"
        Lane.SHELTERED -> " · circle relays"
        Lane.DIRECT -> " · direct"
        null -> " · checking connection"
    } + (if (torOnly) " · Tor only" else "") + (if (quiet) " · quiet" else "")

/** What the line means, for the privacy sheet and screen readers; null while the lane is unknown. */
internal fun privacyMeaning(lane: Lane?, torOnly: Boolean): String? =
    lane?.meaning?.let { if (torOnly) "$it $TOR_ONLY_MEANING" else it }

/**
 * The lane a room's next message takes, by the rule its transport reads by:
 * a Tor-only room's transport counts no relay as the circle's, so neither
 * does its header, whatever boxes the account has marked.
 */
internal fun roomLane(relays: Collection<String>, torOnly: Boolean, circle: () -> Set<String>): Lane? =
    laneOfRelays(relays, if (torOnly) emptySet() else circle())
