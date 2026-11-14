package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.NodeRoomRecord

internal data class NodeJoinView(val node: NodeRoomRecord, val url: String, val busy: Boolean = false,
    val waitingUntil: Long? = null, val message: String? = null)
internal data class NodeHostChoice(val origin: String, val pendingRoom: String? = null)

/**
 * Whether the signed-in [account] already keeps the room a node link names:
 * the account's room list has it ([accountRooms], read only from that
 * account's list), this phone has it saved as that account ([savedAs] is the
 * saved copy's participant), or this phone holds its node custody as that
 * account ([custodyHeld]: made here, its owner key shared, or let in before).
 * Such a person is not put the stranger's "Ask to join this room?"
 * confirmation; the ask still goes to the room's keeper, which lets the
 * account's devices in without asking anybody (11 November 2026).
 *
 * Never the link alone: its record is signed by the room's own key, so a
 * link naming the account's paired node proves nothing about who made it.
 */
internal fun accountKeepsNodeRoom(node: NodeRoomRecord, account: String?, accountRooms: Collection<String>,
    savedAs: String?, custodyHeld: Boolean): Boolean {
    if (account == null) return false
    return node.room in accountRooms || savedAs == account || custodyHeld
}

/**
 * Why this phone could not let itself into a room its own account made, in
 * plain words. Before 14 November 2026 the reason was thrown away and the
 * phone went on to wait for a keeper that might never answer, with nothing
 * on screen to say what had happened.
 */
internal fun ownAdmissionFailure(error: Throwable): String = when {
    error is NodeRequestException && error.alreadyGranted -> "the node already holds another pass for this phone"
    error is NodeRequestException && error.status == 0 -> "the node did not answer"
    error is NodeRequestException && error.status == 429 -> "the node is busy"
    error is NodeRequestException && error.status in 400..499 -> "the node turned this phone's pass away"
    error is NodeRequestException -> "the node had a fault"
    error is java.io.IOException -> "the node could not be reached"
    else -> "it could not finish its pass"
}

/** Shown while an own-room ask waits on the account's other devices. */
internal fun ownAskWaitingWords(reason: String): String =
    "This phone could not let itself in: $reason. It is asking your other devices instead, so one with this room open needs to be online."

/** Shown when a room the account keeps is offered again after the person stopped asking. */
internal const val OWN_ASK_STOPPED = "You stopped asking to join. Ask again when one of your devices with this room open is online."
