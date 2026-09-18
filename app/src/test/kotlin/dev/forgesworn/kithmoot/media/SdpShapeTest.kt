package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What counts as "the same session described again", and what does not.
 *
 * This is the comparison the negotiation machine has to make to notice that two
 * ends of a pair completed different negotiations. Too strict and every
 * retransmission looks like a disagreement and the pair renegotiates forever;
 * too loose and a genuinely different answer slips through and one direction
 * stays dead for the rest of the call. Both failures are silent, so the rule is
 * pinned here rather than inferred from the negotiation tests.
 */
class SdpShapeTest {

    private fun sdp(
        version: Long = 1,
        direction: String = "sendrecv",
        ufrag: String = "abcd",
        candidates: List<String> = emptyList(),
    ): String = buildString {
        append("v=0\r\n")
        append("o=- 4611731400430051336 $version IN IP4 127.0.0.1\r\n")
        append("s=-\r\n")
        append("t=0 0\r\n")
        append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
        append("a=mid:0\r\n")
        append("a=ice-ufrag:$ufrag\r\n")
        append("a=ice-pwd:${ufrag}pwd\r\n")
        append("a=$direction\r\n")
        for (candidate in candidates) append("a=candidate:$candidate\r\n")
    }

    @Test
    fun `a description re-rendered with more candidates has the same shape`() {
        val first = sdp(candidates = listOf("1 1 udp 2113 192.0.2.1 5000 typ host"))
        val later = sdp(
            candidates = listOf(
                "1 1 udp 2113 192.0.2.1 5000 typ host",
                "2 1 udp 1694 198.51.100.7 5001 typ srflx",
                "3 1 udp 41 203.0.113.9 5002 typ relay",
            ),
        )

        assertNotEquals(first, later, "the fixture must differ in bytes or it proves nothing")
        assertTrue(SdpShape.same(first, later))
    }

    @Test
    fun `end-of-candidates is not part of the shape`() {
        assertTrue(SdpShape.same(sdp(), sdp() + "a=end-of-candidates\r\n"))
    }

    @Test
    fun `a bumped session version is not part of the shape`() {
        assertTrue(SdpShape.same(sdp(version = 1), sdp(version = 7)))
    }

    @Test
    fun `a different session id is part of the shape`() {
        val other = sdp().replace("4611731400430051336", "1234567890123456789")
        assertFalse(SdpShape.same(sdp(), other))
    }

    @Test
    fun `a changed direction changes the shape`() {
        // The whole point. These two answers say different things about who is
        // sending audio, and everything else about them is identical.
        assertFalse(SdpShape.same(sdp(direction = "recvonly"), sdp(direction = "sendrecv")))
        assertFalse(SdpShape.same(sdp(direction = "sendonly"), sdp(direction = "inactive")))
    }

    @Test
    fun `changed ICE credentials change the shape`() {
        // An ICE restart is a different negotiation, not the same one described
        // again, and the credentials are the only thing that says so.
        assertFalse(SdpShape.same(sdp(ufrag = "abcd"), sdp(ufrag = "efgh")))
    }

    @Test
    fun `line endings are not part of the shape`() {
        assertTrue(SdpShape.same(sdp(), sdp().replace("\r\n", "\n")))
        assertTrue(SdpShape.same(sdp(), sdp() + "\r\n\r\n"))
    }

    /**
     * What libwebrtc actually renders, before and after it has a candidate.
     *
     * Not a fixture built to pass: the port, the connection line and the `a=rtcp:`
     * line are all rewritten the moment gathering produces something, and a
     * retransmitted description is re-read off the connection, so every real
     * retransmission differs from its original in exactly these places. A shape
     * rule that does not survive them is a rule that never fires on a real call.
     */
    private fun gathering(gathered: Boolean, direction: String = "sendrecv"): String {
        val port = if (gathered) "52193" else "9"
        val host = if (gathered) "192.0.2.15" else "0.0.0.0"
        return buildString {
            append("v=0\r\n")
            append("o=- 4611731400430051336 ${if (gathered) 3 else 2} IN IP4 127.0.0.1\r\n")
            append("s=-\r\n")
            append("t=0 0\r\n")
            append("a=group:BUNDLE 0\r\n")
            append("a=msid-semantic: WMS kithmoot\r\n")
            append("m=audio $port UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126\r\n")
            append("c=IN IP4 $host\r\n")
            if (gathered) {
                append("a=rtcp:$port IN IP4 $host\r\n")
                append("a=candidate:842163049 1 udp 1677729535 192.0.2.15 52193 typ srflx raddr 10.0.0.4 rport 52193\r\n")
                append("a=candidate:1510613869 1 udp 2113937151 10.0.0.4 52193 typ host\r\n")
            } else {
                append("a=rtcp:9 IN IP4 0.0.0.0\r\n")
            }
            append("a=ice-ufrag:4ZcD\r\n")
            append("a=ice-pwd:by/5aMkC7ZfClgLc4SLWZ8CF\r\n")
            append("a=ice-options:trickle renomination\r\n")
            append("a=fingerprint:sha-256 7B:8B:F0:65:5F:78:E2:51:3B:AC:6F:F3:3F:46:1B:35\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:0\r\n")
            append("a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n")
            append("a=$direction\r\n")
            append("a=msid:kithmoot 47017fee-b6c1-4162-929c-a25110252a7d\r\n")
            append("a=rtcp-mux\r\n")
            append("a=rtcp-rsize\r\n")
            append("a=rtpmap:111 opus/48000/2\r\n")
            append("a=rtcp-fb:111 transport-cc\r\n")
            append("a=ssrc:1837903497 cname:tD3Gk0rMUlQ8dEf8\r\n")
        }
    }

    @Test
    fun `gathering does not change the shape`() {
        // The port, the connection line and the rtcp line are all rewritten the
        // moment there is a candidate to put in them. None of them is anything
        // the two ends negotiated.
        assertNotEquals(gathering(gathered = false), gathering(gathered = true))
        assertTrue(SdpShape.same(gathering(gathered = false), gathering(gathered = true)))
    }

    @Test
    fun `a direction change survives gathering`() {
        // And the rule has to keep working through all of that: the answer
        // before the microphone and the answer after it are different sessions
        // however much else has been rewritten in between.
        assertFalse(
            SdpShape.same(
                gathering(gathered = false, direction = "recvonly"),
                gathering(gathered = true, direction = "sendrecv"),
            ),
        )
    }

    @Test
    fun `rtcp-mux, rtcp-fb and rtcp-rsize are part of the shape`() {
        // Only the bare `a=rtcp:` line is a gathering result. These three are
        // what the two ends agreed to do with RTCP.
        val shape = SdpShape.of(gathering(gathered = true))
        assertTrue(shape.contains("a=rtcp-mux"))
        assertTrue(shape.contains("a=rtcp-fb:111 transport-cc"))
        assertTrue(shape.contains("a=rtcp-rsize"))
        assertFalse(shape.contains("a=rtcp:"))
        assertTrue(shape.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126"))
        assertEquals(1, shape.lineSequence().count { it.startsWith("c=") })
    }

    @Test
    fun `the shape keeps the direction and the credentials it is judged on`() {
        val shape = SdpShape.of(sdp(direction = "recvonly", candidates = listOf("1 1 udp 2113 192.0.2.1 5000 typ host")))

        assertTrue(shape.contains("a=recvonly"))
        assertTrue(shape.contains("a=ice-ufrag:abcd"))
        assertTrue(shape.contains("a=ice-pwd:abcdpwd"))
        assertFalse(shape.contains("candidate"))
        assertEquals("o=- 4611731400430051336 0 IN IP4 127.0.0.1", shape.lineSequence().first { it.startsWith("o=") })
    }
}
