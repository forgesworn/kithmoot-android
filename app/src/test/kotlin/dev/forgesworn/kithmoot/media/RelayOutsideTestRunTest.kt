package dev.forgesworn.kithmoot.media

import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure parts of the two-peer run: which addresses it reads, and which pair counts as the path. */
class RelayOutsideTestRunTest {
    @Test fun `the relay's address is its one IPv4 literal`() {
        assertEquals("203.0.113.9", RelayOutsideTestRun.turnHostOf(listOf("turn:203.0.113.9:40000?transport=udp", "turn:203.0.113.9:40001?transport=tcp")))
        assertNull(RelayOutsideTestRun.turnHostOf(listOf("turn:203.0.113.9:40000?transport=udp", "turn:198.51.100.7:40001?transport=tcp")))
        assertNull(RelayOutsideTestRun.turnHostOf(listOf("turn:relay.example:3478?transport=udp")))
    }

    @Test fun `peer B asks the UDP listener for its address, and nothing else`() {
        assertEquals("stun:203.0.113.9:40000", RelayOutsideTestRun.stunFromRelay(listOf("turn:203.0.113.9:40001?transport=tcp", "turn:203.0.113.9:40000?transport=udp")))
        assertNull(RelayOutsideTestRun.stunFromRelay(listOf("turn:203.0.113.9:40001?transport=tcp")))
    }

    @Test fun `a candidate line gives its type and address`() {
        assertEquals("srflx" to "198.51.100.7", RelayOutsideTestRun.candidateTypeAndAddress("candidate:2 1 udp 1 198.51.100.7 40000 typ srflx raddr 0.0.0.0 rport 0"))
        assertEquals("relay" to "203.0.113.9", RelayOutsideTestRun.candidateTypeAndAddress("candidate:3 1 udp 1 203.0.113.9 50000 typ relay raddr 198.51.100.7 rport 1"))
        assertEquals(null to null, RelayOutsideTestRun.candidateTypeAndAddress("garbage"))
    }

    @Test fun `the pair a transport selects is the path, even when it is not marked nominated`() {
        // As a phone on mobile data reported it: peer A's working pair says
        // nominated=false while its transport names it as selected.
        val stats = listOf(
            RTCStats(0, "candidate-pair", "p1", mapOf("state" to "in-progress", "nominated" to false, "bytesReceived" to BigInteger.ZERO, "localCandidateId" to "l1")),
            RTCStats(0, "candidate-pair", "p2", mapOf("state" to "succeeded", "nominated" to false, "bytesReceived" to BigInteger.valueOf(1301), "localCandidateId" to "l1")),
            RTCStats(0, "local-candidate", "l1", mapOf("candidateType" to "relay", "address" to "203.0.113.9")),
            RTCStats(0, "transport", "t1", mapOf("selectedCandidatePairId" to "p2", "dtlsState" to "connected")),
        )
        assertEquals(RelayOutsideTestRun.Path("relay", "203.0.113.9"), RelayOutsideTestRun.selectedPath(RTCStatsReport(0, stats.associateBy { it.id })))
    }

    @Test fun `a selected pair is the path while a fresh check is out, but not once failed or without bytes`() {
        fun report(state: String, received: Any) = RTCStatsReport(0, listOf(
            RTCStats(0, "candidate-pair", "p1", mapOf("state" to state, "nominated" to true, "bytesReceived" to received, "localCandidateId" to "l1")),
            RTCStats(0, "local-candidate", "l1", mapOf("candidateType" to "relay", "address" to "203.0.113.9")),
            RTCStats(0, "transport", "t1", mapOf("selectedCandidatePairId" to "p1")),
        ).associateBy { it.id })
        // As the other peer reported it: the working pair in-progress while a check was out.
        assertEquals(RelayOutsideTestRun.Path("relay", "203.0.113.9"), RelayOutsideTestRun.selectedPath(report("in-progress", BigInteger.TEN)))
        assertNull(RelayOutsideTestRun.selectedPath(report("failed", BigInteger.TEN)))
        assertNull(RelayOutsideTestRun.selectedPath(report("succeeded", BigInteger.ZERO)))
        assertEquals(RelayOutsideTestRun.Path("relay", "203.0.113.9"), RelayOutsideTestRun.selectedPath(report("succeeded", BigInteger.ONE)))
    }

    @Test fun `without a transport, only a nominated, succeeded pair that received bytes is the path`() {
        fun pair(id: String, local: String, state: String, nominated: Boolean, received: Any) =
            RTCStats(0, "candidate-pair", id, mapOf("state" to state, "nominated" to nominated, "bytesReceived" to received, "localCandidateId" to local))
        fun candidate(id: String, type: String, address: String) =
            RTCStats(0, "local-candidate", id, mapOf("candidateType" to type, "address" to address))
        val stats = listOf(
            pair("p1", "l1", "in-progress", true, BigInteger.TEN),
            pair("p2", "l2", "succeeded", false, BigInteger.TEN),
            pair("p3", "l3", "succeeded", true, BigInteger.ZERO),
            candidate("l1", "host", "192.168.1.20"), candidate("l2", "srflx", "198.51.100.7"), candidate("l3", "relay", "203.0.113.9"),
        )
        assertNull(RelayOutsideTestRun.selectedPath(RTCStatsReport(0, stats.associateBy { it.id })))
        val chosen = stats + pair("p4", "l2", "succeeded", true, BigInteger.ONE)
        assertEquals(RelayOutsideTestRun.Path("srflx", "198.51.100.7"), RelayOutsideTestRun.selectedPath(RTCStatsReport(0, chosen.associateBy { it.id })))
    }
}
