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
