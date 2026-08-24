package dev.forgesworn.kithmoot.crypto

import java.security.SecureRandom

private val HEX_ALPHABET = "0123456789abcdef".toCharArray()

/** Lower-case hex, the form every Nostr identifier travels in. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX_ALPHABET[v ushr 4]
        out[i * 2 + 1] = HEX_ALPHABET[v and 0x0f]
    }
    return String(out)
}

/**
 * Case-insensitive equality for hex identifiers.
 *
 * Nostr pubkeys, room ids, and every other hex field this protocol compares
 * are canonically produced in lower case, but nothing on the wire enforces
 * that: an allow-list entry typed or pasted by a person, in particular, may
 * carry upper-case hex naming exactly the same key. Every place this
 * protocol decides whether two hex identifiers name the same thing must go
 * through this function rather than `==`/`!=`, so a case difference is
 * never mistaken for a different identity - see `vectors/README.md`.
 */
fun String.hexEquals(other: String): Boolean = this.equals(other, ignoreCase = true)

/**
 * Canonicalises a hex identifier to lower case.
 *
 * [hexEquals] makes an equality check safe regardless of case, but nothing
 * protects a *lexicographic* comparison the same way: `PeerLink`'s glare
 * tiebreak and `RoleArbiter`'s device tiebreak both order hex strings with
 * `<`, and two implementations that disagree on which of two
 * differently-cased spellings of the same identifier sorts first can reach
 * opposite answers from identical input - the exact deadlock perfect
 * negotiation exists to prevent. Call this once, at the point a hex
 * identifier enters the system - a decoded event, a parsed credential or
 * proof, a pubkey read from storage or a URL - rather than at each
 * comparison site, so every later equality or ordering check on it is
 * correct by construction. See `vectors/README.md`.
 */
fun String.normaliseHex(): String = lowercase()

/** Parses lower- or upper-case hex. Throws on anything that is not hex. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "hex string contains a non-hex character" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * The one source of randomness in the protocol layer. Every function that
 * needs entropy takes it as a defaulted argument instead of reaching for a
 * global, so the interop vectors can pin the exact bytes.
 */
object Entropy {
    private val random = SecureRandom()

    fun bytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }
}
