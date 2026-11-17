package dev.forgesworn.kithmoot.media

import org.junit.Assert.*
import org.junit.Test

class CallConnectionTest {
    private fun row(id: String, type: String, vararg values: Pair<String, Any?>) = CallRouteStat(id, type, values.toMap())
    private val direct = listOf(
        row("transport", "transport", "selectedCandidatePairId" to "pair"),
        row("pair", "candidate-pair", "localCandidateId" to "own", "remoteCandidateId" to "other", "bytesSent" to 100, "bytesReceived" to 200, "currentRoundTripTime" to 0.04),
        row("own", "local-candidate", "candidateType" to "host", "protocol" to "udp", "address" to "192.0.2.1"),
        row("other", "remote-candidate", "candidateType" to "srflx", "address" to "198.51.100.1"),
    )
    private fun relay(url: String) = direct.map { if (it.id == "own") it.copy(members = it.members + mapOf("candidateType" to "relay", "url" to url, "relayProtocol" to "tls")) else it }
    @Test fun selectedDirectRouteIgnoresNominatedBackup() {
        val result = observedCallConnection("connected", direct + row("backup", "candidate-pair", "nominated" to true, "selected" to true))
        assertEquals("Direct to the other device · UDP", result.route)
        assertEquals("Sent 100 bytes · received 200 bytes · round trip 40 ms", result.traffic)
        assertFalse(result.toString().contains("192.0.2"))
    }
    @Test fun unobservedAndNominatedRoutesStayUnknown() {
        assertTrue(observedCallConnection("connected", emptyList()).route.contains("unavailable"))
        assertTrue(observedCallConnection("connected", listOf(row("p", "candidate-pair", "nominated" to true))).route.contains("unavailable"))
    }
    @Test fun selectedFlagSupportsBrowsersWithoutTransportRows() {
        val stats = direct.drop(1).map { if (it.id == "pair") it.copy(members = it.members + ("selected" to true)) else it }
        assertEquals("Direct to the other device · UDP", observedCallConnection("connected", stats).route)
    }
    @Test fun ambiguousIndependentTransportsStayUnknown() {
        assertTrue(observedCallConnection("connected", direct + listOf(row("t2", "transport", "selectedCandidatePairId" to "p2"), row("p2", "candidate-pair"))).route.contains("unavailable"))
    }
    @Test fun localRelayShowsServerAndActualClientTransport() {
        assertEquals("TURN relay · node.example · this device to relay: TLS", observedCallConnection("connected", relay("turns:node.example:443?transport=tcp&token=secret")).route)
    }
    @Test fun credentialsAndNonTurnUrlsAreNotDisplayed() {
        for (url in listOf("turn:user:password@node.example:3478", "https://evil.example", "turn:<script>:3478")) {
            assertTrue(observedCallConnection("connected", relay(url)).route.contains("server name unavailable"))
        }
    }
    @Test fun remoteRelayOperatorIsUnknownAndPeerIpIsNeverExposed() {
        val stats = direct.map { if (it.id == "other") it.copy(members = it.members + ("candidateType" to "relay")) else it }
        val result = observedCallConnection("connected", stats)
        assertTrue(result.route.contains("server name unavailable"))
        assertFalse(result.toString().contains("198.51.100"))
    }
    @Test fun disconnectedRouteIsExplicitlyHistorical() {
        assertTrue(observedCallConnection("disconnected", direct).route.startsWith("Last selected:"))
    }
    @Test fun inboundVideoCountsStayLocalAndInvalidCountersAreZero() {
        val result = observedCallConnection("connected", direct + row("video", "inbound-rtp", "kind" to "video", "framesDecoded" to 123L, "packetsLost" to -1))
        assertEquals("Received video: 123 frames decoded · 0 packets lost", result.video)
    }
}
