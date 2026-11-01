package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host candidates are sent again (1 November 2026), so two devices on one
 * network connect directly. The related address of a reflexive or relay
 * candidate is still hidden, as is a connection address that is not a kept
 * candidate's. Every address below is from a documentation or private range.
 */
class LocalAddressPrivacyTest {
    private fun host(address: String, transport: String = "udp", extra: String = "") =
        "candidate:1 1 $transport 2122260223 $address 54321 typ host$extra generation 0 ufrag abcd network-id 1"

    private fun reflexive(type: String, address: String, related: String, transport: String = "udp") =
        "candidate:2 1 $transport 1686052607 $address 40000 typ $type raddr $related rport 54321 generation 0 ufrag abcd network-id 1"

    // --- candidate lines ----------------------------------------------------

    @Test fun `keeps host candidates unchanged on every kind of address`() {
        val addresses = listOf(
            "192.168.1.20", "10.1.2.3", "172.16.4.5", "10.2.0.2", "100.64.0.9", "100.127.255.254", "169.254.10.20",
            "203.0.113.7", "127.0.0.1", "::1", "2001:db8::1", "fd12:3456:789a::1", "fe80::1c2d:3e4f:5a6b:7c8d",
            "0b1d8e3a-7c2f-4d6e-9a10-5f4e3d2c1b0a.local", "ABCDEF12-3456-7890-ABCD-EF1234567890.LOCAL",
        )
        for (address in addresses) {
            val line = host(address)
            assertSame(address, line, LocalAddressPrivacy.candidate(line))
        }
    }

    @Test fun `keeps TCP host candidates unchanged`() {
        val v4 = host("192.168.1.20", "tcp", " tcptype passive")
        assertSame(v4, LocalAddressPrivacy.candidate(v4))
        val v6 = host("fd00::7", "tcp", " tcptype active")
        assertSame(v6, LocalAddressPrivacy.candidate(v6))
    }

    @Test fun `rewrites the related address of a server reflexive candidate`() {
        val line = reflexive("srflx", "203.0.113.7", "192.168.1.20")
        assertEquals(
            "candidate:2 1 udp 1686052607 203.0.113.7 40000 typ srflx raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-id 1",
            LocalAddressPrivacy.candidate(line),
        )
    }

    @Test fun `rewrites the related address of a peer reflexive and a relay candidate`() {
        assertEquals(
            "candidate:2 1 udp 1686052607 203.0.113.8 40000 typ prflx raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-id 1",
            LocalAddressPrivacy.candidate(reflexive("prflx", "203.0.113.8", "10.0.0.4")),
        )
        assertEquals(
            "candidate:2 1 udp 1686052607 198.51.100.9 40000 typ relay raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-id 1",
            LocalAddressPrivacy.candidate(reflexive("relay", "198.51.100.9", "100.64.1.2")),
        )
    }

    @Test fun `rewrites an IPv6 related address and a TCP reflexive candidate`() {
        assertEquals(
            "candidate:2 1 udp 1686052607 2001:db8::7 40000 typ srflx raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-id 1",
            LocalAddressPrivacy.candidate(reflexive("srflx", "2001:db8::7", "fd12::1")),
        )
        val tcp = "candidate:3 1 tcp 1518280447 203.0.113.7 9 typ srflx raddr 192.168.1.20 rport 9 tcptype active generation 0"
        assertEquals(
            "candidate:3 1 tcp 1518280447 203.0.113.7 9 typ srflx raddr 0.0.0.0 rport 0 tcptype active generation 0",
            LocalAddressPrivacy.candidate(tcp),
        )
    }

    @Test fun `leaves a reflexive candidate that has no related address as it is`() {
        val line = "candidate:2 1 udp 1686052607 203.0.113.7 40000 typ srflx generation 0"
        assertSame(line, LocalAddressPrivacy.candidate(line))
    }

    @Test fun `accepts the a= prefix and a trailing line ending, and keeps both`() {
        val line = "a=" + reflexive("srflx", "203.0.113.7", "192.168.1.20") + "\r\n"
        val out = LocalAddressPrivacy.candidate(line)!!
        assertTrue(out.startsWith("a=candidate:2 1 udp"))
        assertTrue(out.endsWith("raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-id 1\r\n"))
        val hostLine = "a=" + host("192.168.1.20") + "\n"
        assertSame(hostLine, LocalAddressPrivacy.candidate(hostLine))
    }

    @Test fun `drops empty and malformed candidates`() {
        for (bad in listOf(
            "",
            "   ",
            "candidate:",
            "candidate:1 1 udp 2122260223 192.168.1.20 54321",
            "candidate:1 1 udp 2122260223 203.0.113.7 54321 nottyp srflx",
            "candidate:1 1 udp 2122260223 203.0.113.7 54321 typ",
            "candidate:1 1 udp 2122260223 203.0.113.7 54321 typ bogus",
            "not a candidate",
            "a=ice-ufrag:abcd",
        )) {
            assertNull("[$bad]", LocalAddressPrivacy.candidate(bad))
        }
    }

    // --- wire candidates ----------------------------------------------------

    @Test fun `filters a bare wire candidate`() {
        val local = host("192.168.1.20")
        assertSame(local, LocalAddressPrivacy.wireCandidate(local))
        assertEquals(
            "candidate:2 1 udp 1686052607 203.0.113.7 40000 typ srflx raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-id 1",
            LocalAddressPrivacy.wireCandidate(reflexive("srflx", "203.0.113.7", "192.168.1.20")),
        )
    }

    @Test fun `filters a JSON wire candidate and keeps its other fields`() {
        val local = """{"candidate":"${host("10.0.0.4")}","sdpMid":"0","sdpMLineIndex":0}"""
        assertSame(local, LocalAddressPrivacy.wireCandidate(local))

        val mdns = """{"candidate":"${host("0b1d8e3a-7c2f-4d6e-9a10-5f4e3d2c1b0a.local")}","sdpMid":"0","sdpMLineIndex":0}"""
        assertSame(mdns, LocalAddressPrivacy.wireCandidate(mdns))

        val srflx = """{"candidate":"${reflexive("srflx", "203.0.113.7", "192.168.1.20")}","sdpMid":"0","sdpMLineIndex":1,"usernameFragment":"abcd"}"""
        val out = LocalAddressPrivacy.wireCandidate(srflx)!!
        assertTrue(out, out.contains("raddr 0.0.0.0 rport 0"))
        assertFalse(out, out.contains("192.168.1.20"))
        assertTrue(out, out.contains("\"sdpMid\":\"0\"") && out.contains("\"sdpMLineIndex\":1") && out.contains("\"usernameFragment\":\"abcd\""))
    }

    @Test fun `drops wire candidates that are empty or not candidates`() {
        assertNull(LocalAddressPrivacy.wireCandidate(""))
        assertNull(LocalAddressPrivacy.wireCandidate("""{"candidate":"","sdpMid":"0","sdpMLineIndex":0}"""))
        assertNull(LocalAddressPrivacy.wireCandidate("""{"sdpMid":"0"}"""))
        assertNull(LocalAddressPrivacy.wireCandidate("""{"candidate":7}"""))
        assertNull(LocalAddressPrivacy.wireCandidate("{not json"))
        assertNull(LocalAddressPrivacy.wireCandidate("[]"))
    }

    // --- session descriptions -----------------------------------------------

    private fun description(eol: String, vararg candidates: String, connection: String = "IN IP4 192.168.1.20", rtcp: String = "9 IN IP4 192.168.1.20") =
        listOf(
            "v=0",
            "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "a=group:BUNDLE 0",
            "m=audio 54321 UDP/TLS/RTP/SAVPF 111",
            "c=$connection",
            "a=rtcp:$rtcp",
            *candidates.map { "a=$it" }.toTypedArray(),
            "a=end-of-candidates",
            "a=ice-ufrag:abcd",
            "a=mid:0",
            "a=rtcp-mux",
            "a=sendrecv",
        ).joinToString(eol, postfix = eol)

    @Test fun `keeps host candidate lines and the connection and rtcp addresses they name`() {
        val sdp = description("\r\n", host("192.168.1.20"), host("fd00::7"), host("100.64.0.9", "tcp", " tcptype passive"))
        assertEquals(sdp, LocalAddressPrivacy.sdp(sdp))
    }

    @Test fun `hides a connection and rtcp address that no kept candidate names`() {
        val out = LocalAddressPrivacy.sdp(description("\r\n", host("100.64.0.9")))
        assertTrue(out, out.contains("a=candidate:1 1 udp 2122260223 100.64.0.9 54321 typ host"))
        assertTrue(out, out.contains("c=IN IP4 0.0.0.0\r\n"))
        assertTrue(out, out.contains("a=rtcp:9 IN IP4 0.0.0.0\r\n"))
        assertFalse(out, out.contains("192.168.1.20"))
        assertTrue(out.contains("o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n"))
        assertTrue(out.contains("a=end-of-candidates\r\na=ice-ufrag:abcd\r\na=mid:0\r\n"))
    }

    @Test fun `keeps connection addresses that belong to a kept candidate`() {
        val sdp = description(
            "\r\n",
            host("192.168.1.20"),
            reflexive("srflx", "203.0.113.7", "192.168.1.20"),
            connection = "IN IP4 203.0.113.7",
            rtcp = "9 IN IP4 203.0.113.7",
        )
        val out = LocalAddressPrivacy.sdp(sdp)
        assertTrue(out, out.contains("c=IN IP4 203.0.113.7\r\n"))
        assertTrue(out, out.contains("a=rtcp:9 IN IP4 203.0.113.7\r\n"))
        assertTrue(out, out.contains("a=candidate:2 1 udp 1686052607 203.0.113.7 40000 typ srflx raddr 0.0.0.0 rport 0 generation 0 ufrag abcd network-id 1\r\n"))
        // The host candidate is sent again; only the related address behind the mapping is hidden.
        assertTrue(out, out.contains("a=candidate:1 1 udp 2122260223 192.168.1.20 54321 typ host"))
        assertFalse(out, out.contains("raddr 192.168.1.20"))
    }

    @Test fun `a connection address that equals only a malformed candidate is hidden`() {
        val sdp = description("\r\n", "candidate:1 1 udp oops", reflexive("relay", "198.51.100.9", "192.168.1.20"))
        val out = LocalAddressPrivacy.sdp(sdp)
        assertTrue(out, out.contains("c=IN IP4 0.0.0.0\r\n"))
        assertTrue(out, out.contains("a=rtcp:9 IN IP4 0.0.0.0\r\n"))
    }

    @Test fun `keeps an mDNS host candidate and its address`() {
        val name = "0b1d8e3a-7c2f-4d6e-9a10-5f4e3d2c1b0a.local"
        val sdp = description("\r\n", host(name), connection = "IN IP4 $name", rtcp = "9 IN IP4 $name")
        assertEquals(sdp, LocalAddressPrivacy.sdp(sdp))
    }

    @Test fun `keeps loopback host candidates and their address`() {
        val sdp = description("\r\n", host("127.0.0.1"), connection = "IN IP4 127.0.0.1", rtcp = "9 IN IP4 127.0.0.1")
        assertEquals(sdp, LocalAddressPrivacy.sdp(sdp))
    }

    @Test fun `already hidden addresses are left alone`() {
        val sdp = description("\r\n", connection = "IN IP4 0.0.0.0", rtcp = "9 IN IP4 0.0.0.0")
        assertEquals(sdp, LocalAddressPrivacy.sdp(sdp))
    }

    @Test fun `hides an IPv6 connection line and keeps the rtcp port`() {
        val sdp = description("\r\n", host("fd00::8"), connection = "IN IP6 fd00::7", rtcp = "50000 IN IP6 fd00::7")
        val out = LocalAddressPrivacy.sdp(sdp)
        assertTrue(out, out.contains("c=IN IP4 0.0.0.0\r\n"))
        assertTrue(out, out.contains("a=rtcp:50000 IN IP4 0.0.0.0\r\n"))
    }

    @Test fun `a rtcp line without an address is untouched`() {
        val sdp = description("\r\n", connection = "IN IP4 0.0.0.0", rtcp = "9")
        assertEquals(sdp, LocalAddressPrivacy.sdp(sdp))
    }

    @Test fun `preserves line endings, LF only and CRLF`() {
        for (eol in listOf("\r\n", "\n")) {
            val sdp = description(eol, host("192.168.1.20"), reflexive("srflx", "203.0.113.7", "192.168.1.20"))
            val out = LocalAddressPrivacy.sdp(sdp)
            assertFalse(eol, out.contains("raddr 192.168.1.20"))
            assertTrue(eol, out.contains("typ host"))
            if (eol == "\n") assertFalse(out.contains("\r"))
            else assertEquals(out.count { it == '\n' }, out.count { it == '\r' })
            assertTrue(out.endsWith(eol))
        }
    }

    @Test fun `a description without a trailing line ending keeps none`() {
        val sdp = description("\n", host("192.168.1.20"), connection = "IN IP4 100.64.0.9").trimEnd('\n')
        val out = LocalAddressPrivacy.sdp(sdp)
        assertFalse(out.endsWith("\n"))
        assertTrue(out.contains("c=IN IP4 0.0.0.0"))
    }

    @Test fun `empty and malformed descriptions are safe`() {
        assertEquals("", LocalAddressPrivacy.sdp(""))
        assertEquals("not an sdp", LocalAddressPrivacy.sdp("not an sdp"))
        assertEquals("\r\n\r\n", LocalAddressPrivacy.sdp("\r\n\r\n"))
        assertEquals("c=\r\n", LocalAddressPrivacy.sdp("c=\r\n"))
    }

    @Test fun `a malformed candidate line in a description is removed`() {
        val out = LocalAddressPrivacy.sdp(description("\r\n", "candidate:1 1 udp oops", host("192.168.1.20")))
        assertFalse(out, out.contains("oops"))
        assertTrue(out, out.contains("typ host"))
    }

    @Test fun `is idempotent`() {
        val sdp = description("\r\n", host("192.168.1.20"), reflexive("srflx", "203.0.113.7", "192.168.1.20"), connection = "IN IP4 203.0.113.7")
        val once = LocalAddressPrivacy.sdp(sdp)
        assertEquals(once, LocalAddressPrivacy.sdp(once))
    }

    // --- whole signals ------------------------------------------------------

    private fun signal(type: String, sdp: String? = null, candidate: String? = null, candidates: List<String>? = null, seq: Long? = null) =
        SignalBody(type = type, roomId = "room", sdp = sdp, candidate = candidate, candidates = candidates, seq = seq)

    @Test fun `a signal without a description or candidate comes back as it is`() {
        val health = signal("health")
        assertSame(health, LocalAddressPrivacy.signal(health))
        val ack = SignalBody(type = "ack", roomId = "room", ack = 4)
        assertSame(ack, LocalAddressPrivacy.signal(ack))
    }

    @Test fun `an offer is sent with the related addresses behind its mappings hidden`() {
        val out = LocalAddressPrivacy.signal(signal("offer", sdp = description("\r\n", host("192.168.1.20"), reflexive("srflx", "203.0.113.7", "192.168.1.20")), seq = 1))!!
        assertTrue(out.sdp!!.contains("typ host"))
        assertFalse(out.sdp!!.contains("raddr 192.168.1.20"))
        assertEquals(1L, out.seq)
    }

    @Test fun `a lone host candidate is sent as it is`() {
        val lone = signal("ice", candidate = host("192.168.1.20"))
        assertSame(lone, LocalAddressPrivacy.signal(lone))
        val numbered = signal("ice", candidate = host("192.168.1.20"), seq = 5)
        assertSame(numbered, LocalAddressPrivacy.signal(numbered))
    }

    @Test fun `a lone malformed candidate is skipped without a sequence number, and sent empty with one`() {
        assertNull(LocalAddressPrivacy.signal(signal("ice", candidate = "candidate:1 1 udp oops")))
        val out = LocalAddressPrivacy.signal(signal("ice", candidate = "candidate:1 1 udp oops", seq = 5))!!
        assertNull(out.candidate)
        assertEquals(emptyList<String>(), out.candidates)
        assertEquals(5L, out.seq)
    }

    @Test fun `a batch keeps its host candidates and rewrites the rest`() {
        val out = LocalAddressPrivacy.signal(
            signal(
                "ice",
                candidates = listOf(
                    """{"candidate":"${host("192.168.1.20")}","sdpMid":"0","sdpMLineIndex":0}""",
                    """{"candidate":"${reflexive("srflx", "203.0.113.7", "192.168.1.20")}","sdpMid":"0","sdpMLineIndex":0}""",
                    """{"candidate":"${host("0b1d8e3a-7c2f-4d6e-9a10-5f4e3d2c1b0a.local")}","sdpMid":"0","sdpMLineIndex":0}""",
                ),
                seq = 9,
            ),
        )!!
        assertEquals(3, out.candidates!!.size)
        assertTrue(out.candidates!![0].contains("192.168.1.20"))
        assertFalse(out.candidates!![1].contains("192.168.1.20"))
        assertTrue(out.candidates!![1].contains("raddr 0.0.0.0 rport 0"))
    }

    @Test fun `a signal that is already clean is returned unchanged`() {
        val clean = signal("ice", candidate = """{"candidate":"${host("0b1d8e3a-7c2f-4d6e-9a10-5f4e3d2c1b0a.local")}","sdpMid":"0","sdpMLineIndex":0}""", seq = 2)
        assertSame(clean, LocalAddressPrivacy.signal(clean))
    }
}
