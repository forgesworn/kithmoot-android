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
