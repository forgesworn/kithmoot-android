package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex

/**
 * Bech32 (BIP-173), as NIP-19 uses it: `npub1…` for a public key and
 * `nsec1…` for a secret one. An npub is an encoding of the key, not a hash of
 * it; the same key always gives the same npub, and the npub gives the key back.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encode(hrp: String, data: ByteArray): String {
        val words = requireNotNull(convertBits(IntArray(data.size) { data[it].toInt() and 0xff }, 8, 5, pad = true))
        val checksum = checksum(hrp, words)
        return buildString {
            append(hrp).append('1')
            for (w in words + checksum) append(CHARSET[w])
        }
    }

    /** The prefix and the bytes, or null for anything that is not well-formed bech32. */
    fun decode(text: String): Pair<String, ByteArray>? {
        val lower = text.trim().lowercase()
        if (lower != text.trim() && text.trim().uppercase() != text.trim()) return null
        val split = lower.lastIndexOf('1')
        if (split < 1 || split + 7 > lower.length || lower.length > 1023) return null
        val hrp = lower.substring(0, split)
        if (hrp.any { it.code < 33 || it.code > 126 }) return null
        val words = IntArray(lower.length - split - 1)
        for (i in words.indices) {
            val index = CHARSET.indexOf(lower[split + 1 + i])
            if (index < 0) return null
            words[i] = index
        }
        if (polymod(expand(hrp) + words.toList()) != 1) return null
        val payload = words.copyOfRange(0, words.size - 6)
        val bytes = convertBits(payload, 5, 8, pad = false) ?: return null
        return hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private fun checksum(hrp: String, words: IntArray): IntArray {
        val values = expand(hrp) + words.toList() + List(6) { 0 }
        val mod = polymod(values) xor 1
        return IntArray(6) { (mod shr (5 * (5 - it))) and 31 }
    }

    private fun expand(hrp: String): List<Int> = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top shr i) and 1 == 1) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (value in data) {
            if (value < 0 || value shr from != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }
}

/** `npub1…` for a 32-byte public key given as hex. */
fun npubOf(pubkeyHex: String): String = Bech32.encode("npub", pubkeyHex.hexToBytes())

/**
 * Both ends of the npub: the start is what a person recognises from their
 * profile, the end is what tells two keys with the same start apart, and what
 * they check against the key their signer shows.
 */
fun shortNpub(pubkeyHex: String): String {
    val npub = npubOf(pubkeyHex)
    return npub.take(13) + "…" + npub.takeLast(6)
}

/** A public key from `npub1…` or 64 hex characters, as hex; null for anything else. */
fun publicKeyFrom(text: String): String? {
    val clean = text.trim()
    if (clean.matches(Regex("[0-9a-fA-F]{64}"))) return clean.lowercase()
    val (hrp, bytes) = Bech32.decode(clean) ?: return null
    return if (hrp == "npub" && bytes.size == 32) bytes.toHex() else null
}

/** A secret key from `nsec1…` or 64 hex characters; null for anything else. */
fun secretKeyFrom(text: String): ByteArray? {
    val clean = text.trim()
    if (clean.matches(Regex("[0-9a-fA-F]{64}"))) return clean.hexToBytes()
    val (hrp, bytes) = Bech32.decode(clean) ?: return null
    return if (hrp == "nsec" && bytes.size == 32) bytes else null
}
