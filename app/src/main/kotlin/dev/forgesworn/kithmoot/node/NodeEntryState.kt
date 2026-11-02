package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.NodeRoomRecord

internal data class NodeJoinView(val node: NodeRoomRecord, val url: String, val busy: Boolean = false,
    val waitingUntil: Long? = null, val message: String? = null)
internal data class NodeHostChoice(val origin: String, val pendingRoom: String? = null)
