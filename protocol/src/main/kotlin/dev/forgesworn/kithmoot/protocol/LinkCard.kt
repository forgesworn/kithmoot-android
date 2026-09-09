package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Ed25519Strict
import dev.forgesworn.kithmoot.crypto.toHex
import java.net.IDN
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * A forgesworn-link address card (FSL-CARD-1), verified by SPEC §2.3 rules
 * 1 to 8, written from the specification and checked against the contact
 * card vectors. Rule 9, the expected node id, is applied by [refreshBox],
 * which is where a client holds one. A client only reads these; a box
 * writes them.
 */
class LinkHint(val kind: Int, val value: ByteArray)

class LinkCard(
    val nodeId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val serial: Long,
    val hints: List<LinkHint>,
    val relays: List<String>,
    val onions: List<String>,
)

sealed class LinkVerdict {
    class Ok(val card: LinkCard) : LinkVerdict()
    class Refused(val rule: Int, val reason: String) : LinkVerdict()
}

object LinkCards {
    const val MAX_BYTES: Int = 4096
    const val MIN_BYTES: Int = 126
    const val MAX_AGE_SECONDS: Long = 604800
    const val MAX_SAFE: Long = (1L shl 53) - 1

    private val DOMAIN = "forgesworn-link/card/v1".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private val ONION_HOST = Regex("^[a-z2-7]{56}$")
    private val DNS_HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")
    private val IP6_HOST = Regex("^\\[[0-9a-f:.]+]$")

    /**
     * Is this a relay URL a client may dial? `wss://`, a URL with a DNS name
     * or IP literal as host, no credentials, no fragment, no comma, no
     * unprintable character: word for word forgesworn-link SPEC §2.2.
     */
    fun isRelayUrl(s: String): Boolean {
        if (s.isEmpty() || s.length > 255 || s.contains(',') || !s.startsWith("wss://")) return false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Text.isUrlUnprintable(cp)) return false
            i += Character.charCount(cp)
        }
        val uri = try { URI(s) } catch (_: Exception) { return false }
        if (uri.scheme != "wss" || uri.userInfo != null || !uri.fragment.isNullOrEmpty()) return false
        if (uri.port > 65535) return false
        val rawHost = uri.host ?: return false
        val host = try { IDN.toASCII(rawHost).lowercase() } catch (_: Exception) { return false }
        return DNS_HOST.matches(host) || IP6_HOST.matches(host)
    }

    fun verify(bytes: ByteArray, now: Long, highestSerial: Long? = null): LinkVerdict {
        if (bytes.size < MIN_BYTES || bytes.size > MAX_BYTES) return LinkVerdict.Refused(1, "length")
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "FSL1" || bytes[4].toInt() != 1) return LinkVerdict.Refused(2, "magic or version")
        val count = bytes[61].toInt() and 0xff
        if (count > 16) return LinkVerdict.Refused(3, "hint count")
        val end = bytes.size - 64
        var off = 62
        val hints = ArrayList<LinkHint>()
        val relays = ArrayList<String>()
        val onions = ArrayList<String>()
        for (i in 0 until count) {
            if (off + 3 > end) return LinkVerdict.Refused(3, "hint runs past the signature")
            val kind = bytes[off].toInt() and 0xff
            val len = ((bytes[off + 1].toInt() and 0xff) shl 8) or (bytes[off + 2].toInt() and 0xff)
            if (kind == 0x01 && (len == 0 || len > 255)) return LinkVerdict.Refused(3, "relay hint length")
            if (kind == 0x02 && len != 18) return LinkVerdict.Refused(3, "udp hint length")
            if (kind == 0x03 && len != 58) return LinkVerdict.Refused(3, "onion hint length")
            if (kind == 0x04 && len != 33) return LinkVerdict.Refused(3, "ephemeral hint length")
            val start = off + 3
            off = start + len
            if (off > end) return LinkVerdict.Refused(3, "hint runs past the signature")
            val value = bytes.copyOfRange(start, off)
            hints.add(LinkHint(kind, value))
            if (kind == 0x01) {
                val s = Text.strictUtf8(value) ?: return LinkVerdict.Refused(3, "relay hint utf-8")
                if (value.size >= 3 && value[0] == 0xef.toByte() && value[1] == 0xbb.toByte() && value[2] == 0xbf.toByte()) return LinkVerdict.Refused(3, "relay hint utf-8")
                if (!isRelayUrl(s)) return LinkVerdict.Refused(3, "relay hint url")
                relays.add(s)
            }
            if (kind == 0x03) {
                val host = String(value, 0, 56, Charsets.ISO_8859_1)
                val port = ((value[56].toInt() and 0xff) shl 8) or (value[57].toInt() and 0xff)
                if (!ONION_HOST.matches(host) || port == 0) return LinkVerdict.Refused(3, "onion hint")
                onions.add("$host.onion:$port")
            }
            if (kind == 0x04 && value[0].toInt() != 0x02 && value[0].toInt() != 0x03) return LinkVerdict.Refused(3, "ephemeral hint prefix")
        }
        if (off != end) return LinkVerdict.Refused(3, "hints do not end at the signature")
        val buffer = ByteBuffer.wrap(bytes)
        val issuedRaw = buffer.getLong(37)
        val expiresRaw = buffer.getLong(45)
        val serialRaw = buffer.getLong(53)
        // u64 fields: a value with the top bit set, or above 2^53 - 1, is not one a client can hold.
        if (serialRaw < 0 || serialRaw > MAX_SAFE) return LinkVerdict.Refused(3, "serial too large")
        val nodeId = bytes.copyOfRange(5, 37)
        if (!Ed25519Strict.verifyStrict(bytes.copyOfRange(end, bytes.size), DOMAIN + bytes.copyOfRange(0, end), nodeId)) return LinkVerdict.Refused(4, "signature")
        val issuedAt = if (issuedRaw < 0 || issuedRaw > MAX_SAFE) MAX_SAFE else issuedRaw
        val expiresAt = if (expiresRaw < 0 || expiresRaw > MAX_SAFE) MAX_SAFE else expiresRaw
        if (issuedAt > now + 300) return LinkVerdict.Refused(5, "issued in the future")
        if (expiresAt <= now) return LinkVerdict.Refused(6, "expired")
        if (expiresAt <= issuedAt || expiresAt - issuedAt > MAX_AGE_SECONDS) return LinkVerdict.Refused(7, "expiry window")
        if (highestSerial != null && serialRaw <= highestSerial) return LinkVerdict.Refused(8, "stale serial")
        return LinkVerdict.Ok(LinkCard(nodeId.toHex(), issuedAt, expiresAt, serialRaw, hints, relays, onions))
    }

    /** §3 step 6 of the contact card draft: a fresh card only under the node id the person endorsed. */
    fun refreshBox(pinnedNodeId: String, fresh: ByteArray, now: Long, highestSerial: Long? = null): LinkVerdict {
        val pin = pinnedNodeId.lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(pin)) return LinkVerdict.Refused(9, "pinned node id is not 32 bytes of hex")
        val v = verify(fresh, now, highestSerial)
        if (v is LinkVerdict.Refused) return v
        val card = (v as LinkVerdict.Ok).card
        if (card.nodeId != pin) return LinkVerdict.Refused(9, "node id is not the endorsed one")
        return v
    }
}

/** Text rules shared by the card readers: strict UTF-8 and Unicode categories. */
object Text {
    fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    /** Control, format, separator, surrogate, unassigned and private-use characters have no place in a URL. */
    fun isUrlUnprintable(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE -> true
        else -> false
    }
}
