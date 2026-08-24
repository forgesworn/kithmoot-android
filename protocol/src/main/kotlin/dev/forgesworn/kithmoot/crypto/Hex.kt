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
