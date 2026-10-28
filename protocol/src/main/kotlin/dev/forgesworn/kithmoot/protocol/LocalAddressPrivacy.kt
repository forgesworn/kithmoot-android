package dev.forgesworn.kithmoot.protocol

import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps this device's local network addresses out of the signals a call sends.
 *
 * A raw `host` candidate names an interface address: the LAN address, a VPN
 * tunnel, a Tailscale 100.x address, a mesh ULA. Those are stable identifiers
 * that link one person across VPN sessions and rooms. A server-reflexive
 * candidate carries the same address in `raddr`. Neither is needed to connect:
 * `srflx` and `relay` candidates reach the far end, at the cost that two devices
 * on one network meet through their router or a relay rather than directly.
 *
 * The rule applies to what is sent, never to what is received, and it is the
 * same rule the web and desktop clients apply:
 *
 * 1. A `host` candidate whose address is an IP literal is dropped, unless the
 *    address is loopback. A `host` candidate whose address is an mDNS name
 *    (`*.local`) is kept. `srflx`, `prflx` and `relay` candidates are kept with
 *    `raddr` and `rport` rewritten to `0.0.0.0` and `0`. UDP and TCP alike. A
 *    candidate that cannot be read is dropped.
 * 2. In a description, `a=candidate:` lines follow rule 1, and the `c=` and
 *    `a=rtcp:` addresses become `0.0.0.0` unless they equal the address of a
 *    candidate that was kept. Nothing else changes, line endings included.
 *
 * Pure functions on strings, so every edge is testable without a device.
 */
object LocalAddressPrivacy {
    private const val UNSPECIFIED = "0.0.0.0"
    private const val CANDIDATE = "candidate:"
    private val KEPT_TYPES = setOf("host", "srflx", "prflx", "relay")
    private val MDNS_NAME = Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*\\.local$", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
    private val IPV6_CHARS = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9_.-]+)?$")
    private val RADDR = Regex("(\\sraddr\\s+)\\S+")
    private val RPORT = Regex("(\\srport\\s+)\\S+")
    private val SPACES = Regex("\\s+")
    private val AFTER_NEWLINE = Regex("(?<=\n)")

    /** One parsed candidate: the text after `candidate:`, and the fields the rule reads. */
    private class Parsed(val body: String, val address: String, val type: String)

    private fun parse(candidate: String): Parsed? {
        val text = candidate.trim().removePrefix("a=")
        if (!text.startsWith(CANDIDATE)) return null
        val body = text.removePrefix(CANDIDATE)
        val fields = body.split(SPACES)
        // foundation component transport priority address port "typ" type
        if (fields.size < 8 || fields[6] != "typ") return null
        if (fields[0].isEmpty() || fields[4].isEmpty()) return null
        return Parsed(body, fields[4], fields[7])
    }

    private fun isIpv4(address: String): Boolean =
        IPV4.matches(address) && address.split('.').all { it.toInt() <= 255 }

    private fun isIpLiteral(address: String): Boolean =
        isIpv4(address) || (address.contains(':') && IPV6_CHARS.matches(address))

    private fun isLoopback(address: String): Boolean {
        // Anything that is not a well-formed literal is never handed to the
        // resolver, so this cannot ask the network anything.
        if (isIpv4(address)) return address.startsWith("127.")
        return try { InetAddress.getByName(address).isLoopbackAddress } catch (_: Exception) { false }
    }

    /** Whether a candidate of this type and address may be sent. */
    private fun mayLeave(candidate: Parsed): Boolean = when (candidate.type) {
        "host" -> if (isIpLiteral(candidate.address)) isLoopback(candidate.address) else MDNS_NAME.matches(candidate.address)
        in KEPT_TYPES -> true
        else -> false
    }

    private fun hideRelatedAddress(candidate: Parsed): String {
        if (candidate.type == "host") return candidate.body
        return candidate.body
            .replace(RADDR) { "${it.groupValues[1]}$UNSPECIFIED" }
            .replace(RPORT) { "${it.groupValues[1]}0" }
    }

    /**
     * One candidate attribute, as `candidate:...` or `a=candidate:...` with or
     * without a trailing line ending. Returns the form that may be sent, in the
     * form it came in, or null when it must not be sent.
     */
    fun candidate(line: String): String? {
        val parsed = parse(line) ?: return null
        if (!mayLeave(parsed)) return null
        val hidden = hideRelatedAddress(parsed)
        if (hidden == parsed.body) return line
        val start = line.indexOf(CANDIDATE) + CANDIDATE.length
        val trailing = line.length - line.trimEnd().length
        return line.substring(0, start) + hidden + line.substring(line.length - trailing)
    }

    /**
     * A candidate as the signal's `candidate` or `candidates` field carries it:
     * a bare `candidate:` string, or the browser's JSON `RTCIceCandidateInit`.
     * Null means do not send it.
     */
    fun wireCandidate(wire: String): String? {
        if (wire.startsWith(CANDIDATE) || wire.startsWith("a=$CANDIDATE")) return candidate(wire)
        val value = try { Json.parseToJsonElement(wire) as? JsonObject } catch (_: Exception) { null } ?: return null
        val text = (value["candidate"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val kept = candidate(text) ?: return null
        if (kept == text) return wire
        return JsonObject(value + ("candidate" to JsonPrimitive(kept))).toString()
    }

    /**
     * A session description with the rule applied. Lines are handled one at a
     * time and their endings are kept as they were, so an offer written with
     * CRLF stays CRLF and one written with LF stays LF.
     */
    fun sdp(sdp: String): String {
        if (sdp.isEmpty()) return sdp
        val lines = sdp.split(AFTER_NEWLINE)
        val kept = HashSet<String>()
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (!line.startsWith("a=candidate:")) continue
            val parsed = parse(line) ?: continue
            if (mayLeave(parsed)) kept += parsed.address
        }
        val out = StringBuilder(sdp.length)
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            val ending = raw.substring(line.length)
            when {
                line.startsWith("a=candidate:") -> candidate(raw)?.let { out.append(it) }
                line.startsWith("c=") -> out.append(connectionLine(line, kept)).append(ending)
                line.startsWith("a=rtcp:") -> out.append(rtcpLine(line, kept)).append(ending)
                else -> out.append(raw)
            }
        }
        return out.toString()
    }

    // c=<nettype> <addrtype> <address>[/ttl[/count]]
    private fun connectionLine(line: String, kept: Set<String>): String {
        val fields = line.substring(2).trim().split(SPACES)
        if (fields.size < 3) return line
        if (fields[2].substringBefore('/') in kept) return line
        return "c=IN IP4 $UNSPECIFIED"
    }

    // a=rtcp:<port> [<nettype> <addrtype> <address>]
    private fun rtcpLine(line: String, kept: Set<String>): String {
        val fields = line.substring("a=rtcp:".length).trim().split(SPACES)
        if (fields.size < 4) return line
        if (fields[3] in kept) return line
        return "a=rtcp:${fields[0]} IN IP4 $UNSPECIFIED"
    }

    /**
     * A signal with the rule applied to whatever it carries, or null when it
     * would carry nothing and should not be sent at all. A signal that carries
     * no description or candidate comes back as it is.
     *
     * Dropping a candidate from a numbered sequence would leave a hole the far
     * end waits on, so candidates are dropped before sequence numbers are given
     * out (see `PeerLink.onLocalCandidate`). This is the last line of defence
     * for whatever slips past that, not the way candidates are normally
     * filtered: a numbered signal is sent empty rather than skipped.
     */
    fun signal(body: SignalBody): SignalBody? {
        if (body.sdp == null && body.candidate == null && body.candidates == null) return body
        val sdp = body.sdp?.let(::sdp)
        val single = body.candidate?.let(::wireCandidate)
        val batch = body.candidates?.mapNotNull(::wireCandidate)
        if (body.candidate != null && single == null && batch == null && sdp == null) {
            return if (body.seq == null) null else body.copy(candidate = null, candidates = emptyList())
        }
        if (sdp == body.sdp && single == body.candidate && batch == body.candidates) return body
        return body.copy(sdp = sdp, candidate = single, candidates = batch)
    }
}
