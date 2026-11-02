package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.NodeJoinRequest

data class NodeHostedRoomView(val host: String, val keeper: Boolean, val busy: Boolean = false,
    val pendingClose: Boolean = false, val message: String? = null,
    val members: List<Pair<String, String>> = emptyList(), val requests: List<NodeJoinRequest> = emptyList())
