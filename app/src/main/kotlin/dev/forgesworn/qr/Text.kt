package dev.forgesworn.qr

// UTF-8 decoding by hand, so a malformed byte becomes U+FFFD exactly as the
// specification says on every platform. Bytes are Ints holding 0..255.

/** Length of the well-formed UTF-8 sequence at [i], or 0 if it is not one. */
private fun sequence(bytes: IntArray, i: Int): Int {
    val b = bytes[i]
    if (b < 0x80) return 1
    val length = if (b in 0xc2..0xdf) 2 else if (b in 0xe0..0xef) 3 else if (b in 0xf0..0xf4) 4 else 0
    if (length == 0 || i + length > bytes.size) return 0
    val second = bytes[i + 1]
    val low = if (b == 0xe0) 0xa0 else if (b == 0xf0) 0x90 else 0x80
    val high = if (b == 0xed) 0x9f else if (b == 0xf4) 0x8f else 0xbf
    if (second < low || second > high) return 0
    for (k in 2 until length) if ((bytes[i + k] and 0xc0) != 0x80) return 0
    return length
}

/** True when [bytes] is entirely well-formed UTF-8. */
internal fun isUtf8(bytes: IntArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        val n = sequence(bytes, i)
        if (n == 0) return false
        i += n
    }
    return true
}

/** Decodes UTF-8, replacing each ill-formed byte with U+FFFD. */
internal fun fromUtf8(bytes: IntArray): String {
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val n = sequence(bytes, i)
        if (n == 0) {
            out.append('�')
            i++
            continue
        }
        val b = bytes[i]
        var cp = if (n == 1) b else b and (0xff ushr (n + 1))
        for (k in 1 until n) cp = (cp shl 6) or (bytes[i + k] and 0x3f)
        out.appendCodePoint(cp)
        i += n
    }
    return out.toString()
}
