package dev.forgesworn.kithmoot.media

/** Local-only route details. Never contains a peer address, SDP or credentials. */
data class CallConnection(val state: String, val route: String, val traffic: String, val video: String)
data class NamedCallConnection(val name: String, val device: String, val connection: CallConnection)
data class CallConnectionsSnapshot(val room: List<String> = emptyList(), val people: List<NamedCallConnection> = emptyList())
internal data class CallRouteStat(val id: String, val type: String, val members: Map<String, Any?>)

internal fun observedCallConnection(state: String, stats: List<CallRouteStat>): CallConnection {
    val transports = stats.filter { it.type == "transport" && it.members["selectedCandidatePairId"] is String }
    val pair = stats.filter { it.type == "candidate-pair" &&
        if (transports.isNotEmpty()) transports.any { t -> t.members["selectedCandidatePairId"] == it.id }
        else it.members["selected"] == true }.singleOrNull()
    val local = pair?.let { p -> stats.find { it.type == "local-candidate" && it.id == p.members["localCandidateId"] } }?.members
    val remote = pair?.let { p -> stats.find { it.type == "remote-candidate" && it.id == p.members["remoteCandidateId"] } }?.members
    fun protocol(value: Any?) = (value as? String)?.uppercase()?.takeIf { it in setOf("UDP", "TCP", "TLS") }
    fun count(value: Any?): Long = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE }?.toLong() ?: 0
    var route = "Selected media route unavailable"
    if (pair != null && local?.get("candidateType") is String && remote?.get("candidateType") is String) {
        val localRelay = local["candidateType"] == "relay"
        val remoteRelay = remote["candidateType"] == "relay"
        route = if (localRelay || remoteRelay) {
            val url = if (localRelay) local["url"] as? String else null
            val host = url?.let { Regex("(?i)^turns?:(?:/{2})?(\\[[a-f0-9:]+]|[a-z0-9._-]+)(?::[0-9]+)?(?:\\?[^\\s]*)?$").matchEntire(it)?.groupValues?.get(1) }
            (if (host != null) "TURN relay · ${host.removeSurrounding("[", "]")}" else "Through a TURN relay · server name unavailable") +
                (if (localRelay) protocol(local["relayProtocol"])?.let { " · this device to relay: $it" }.orEmpty() else "") +
                (if (remoteRelay) " · the other end also uses a relay" else "")
        } else "Direct to the other device" + protocol(local["protocol"])?.let { " · $it" }.orEmpty()
        if (state != "connected") route = "Last selected: $route"
    }
    val rtt = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it < 1e6 }
    val video = stats.filter { it.type == "inbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "video" && it.members["isRemote"] != true }
    return CallConnection(state, route,
        if (pair == null) "Traffic measurements unavailable" else "Sent ${count(pair.members["bytesSent"])} bytes · received ${count(pair.members["bytesReceived"])} bytes" + (rtt?.let { " · round trip ${kotlin.math.round(it * 1000).toLong()} ms" } ?: ""),
        if (video.isEmpty()) "No inbound video measurements" else "Received video: ${video.sumOf { count(it.members["framesDecoded"]) }} frames decoded · ${video.sumOf { count(it.members["packetsLost"]) }} packets lost")
}
