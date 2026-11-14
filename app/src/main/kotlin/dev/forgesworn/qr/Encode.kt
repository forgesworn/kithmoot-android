package dev.forgesworn.qr

import kotlin.math.abs

/** Error correction level: about 7, 15, 25 or 30 per cent of codewords recoverable. */
enum class ErrorCorrection { L, M, Q, H }

/** Data mode of the single segment a code carries. */
enum class Mode { NUMERIC, ALPHANUMERIC, BYTE }

/**
 * Options for [encode].
 *
 * @property ecc Error correction level. Default [ErrorCorrection.M].
 * @property version Exact version, 1-40. Default: the smallest version, from [minVersion], that fits.
 * @property minVersion Smallest version to consider when choosing one. Default 1.
 * @property mask Mask pattern, 0-7. Default: the pattern with the lowest penalty score.
 * @property mode Data mode. Default for text: numeric if every character is a digit, alphanumeric if
 *   every character is in that set, otherwise byte. Bytes are always byte mode.
 * @property eci In byte mode, prefix an ECI designator for UTF-8 (assignment 26) so a reader need not
 *   guess the encoding. Default false: most readers assume UTF-8 for byte mode, and the designator
 *   costs 12 bits.
 */
data class EncodeOptions(
    val ecc: ErrorCorrection = ErrorCorrection.M,
    val version: Int? = null,
    val minVersion: Int = 1,
    val mask: Int? = null,
    val mode: Mode? = null,
    val eci: Boolean = false,
)

/**
 * An encoded symbol.
 *
 * @property size Modules per side, 17 + 4 * version. Excludes the quiet zone.
 * @property modules Row-major modules, `size * size` entries, 1 for dark.
 */
class QrCode(
    val version: Int,
    val ecc: ErrorCorrection,
    val mask: Int,
    val mode: Mode,
    val size: Int,
    val modules: ByteArray,
) {
    /** True if the module at column [x], row [y] is dark. */
    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x].toInt() == 1

    override fun toString(): String = "QrCode(version=$version, ecc=$ecc, mask=$mask, mode=$mode, size=$size)"
}

/** Thrown when the data does not fit the requested or any version. */
class QrCapacityError(message: String) : IllegalArgumentException(message)

/** True if the module at column [x], row [y] of [qr] is dark. */
fun isDark(qr: QrCode, x: Int, y: Int): Boolean = qr.isDark(x, y)

internal const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
internal val EC_ORDER: Array<ErrorCorrection> = arrayOf(ErrorCorrection.L, ErrorCorrection.M, ErrorCorrection.Q, ErrorCorrection.H)
private val EC_FORMAT_BITS: IntArray = intArrayOf(1, 0, 3, 2) // L, M, Q, H
private fun modeBits(mode: Mode): Int = when (mode) {
    Mode.NUMERIC -> 1
    Mode.ALPHANUMERIC -> 2
    Mode.BYTE -> 4
}

private fun countBitsFor(mode: Mode, band: Int): Int = when (mode) {
    Mode.NUMERIC -> intArrayOf(10, 12, 14)[band]
    Mode.ALPHANUMERIC -> intArrayOf(9, 11, 13)[band]
    Mode.BYTE -> intArrayOf(8, 16, 16)[band]
}

private const val ECI_MODE = 7
private const val ECI_UTF8 = 26

/** Encodes text (as UTF-8 in byte mode) as one QR code. */
fun encode(text: String, options: EncodeOptions = EncodeOptions()): QrCode = encodeData(text, null, options)

/** Encodes bytes, always in byte mode, as one QR code. */
fun encode(data: ByteArray, options: EncodeOptions = EncodeOptions()): QrCode = encodeData(null, data, options)

private fun encodeData(text: String?, bytes: ByteArray?, options: EncodeOptions): QrCode {
    val ecc = options.ecc
    val mask = options.mask
    if (mask != null && !(mask in 0..7)) {
        throw IllegalArgumentException("Mask must be an integer from 0 to 7")
    }
    val fixedVersion = options.version
    if (fixedVersion != null && !validVersion(fixedVersion)) {
        throw IllegalArgumentException("Version must be an integer from 1 to 40")
    }
    val minVersion = options.minVersion
    if (!validVersion(minVersion)) throw IllegalArgumentException("minVersion must be an integer from 1 to 40")

    val mode = chooseMode(text, options.mode)
    val payload = segmentBits(text, bytes, mode)
    val eci = mode == Mode.BYTE && options.eci

    val version = fixedVersion ?: chooseVersion(mode, payload, eci, ecc, minVersion)
    val capacity = dataCodewords(version, ecc) * 8
    val countBits = countBitsFor(mode, countBand(version))
    if (payload.count >= (1 shl countBits) || headerLength(countBits, eci) + payload.bits.size > capacity) {
        throw QrCapacityError("Data does not fit version $version at level $ecc")
    }

    val bits = ArrayList<Int>(capacity)
    if (eci) {
        push(bits, ECI_MODE, 4)
        push(bits, ECI_UTF8, 8)
    }
    push(bits, modeBits(mode), 4)
    push(bits, payload.count, countBits)
    for (bit in payload.bits) bits.add(bit)
    var t = 0
    while (t < 4 && bits.size < capacity) {
        bits.add(0)
        t++
    }
    while (bits.size % 8 != 0) bits.add(0)
    val codewords = IntArray(capacity / 8)
    var i = 0
    while (i < bits.size) {
        var byte = 0
        for (j in 0 until 8) byte = (byte shl 1) or bits[i + j]
        codewords[i / 8] = byte
        i += 8
    }
    var pad = 0xec
    var k = bits.size / 8
    while (k < codewords.size) {
        codewords[k] = pad
        pad = pad xor (0xec xor 0x11)
        k++
    }

    val stream = interleave(codewords, version, ecc)
    val grid = Grid(version)
    grid.placeData(stream)

    var chosen = mask ?: 0
    val modules: ByteArray
    if (mask == null) {
        var best = Int.MAX_VALUE
        var bestModules = ByteArray(0)
        for (m in 0 until 8) {
            val candidate = grid.render(ecc, m)
            val score = penalty(candidate, grid.size)
            if (score < best) {
                best = score
                chosen = m
                bestModules = candidate
            }
        }
        modules = bestModules
    } else {
        modules = grid.render(ecc, chosen)
    }
    return QrCode(version, ecc, chosen, mode, grid.size, modules)
}

private fun validVersion(v: Int): Boolean = v in 1..40

internal fun countBand(version: Int): Int = if (version <= 9) 0 else if (version <= 26) 1 else 2

private fun headerLength(countBits: Int, eci: Boolean): Int = (if (eci) 12 else 0) + 4 + countBits

private fun chooseMode(text: String?, requested: Mode?): Mode {
    if (text == null) {
        if (requested != null && requested != Mode.BYTE) throw IllegalArgumentException("Bytes can only be encoded in byte mode")
        return Mode.BYTE
    }
    if (requested == Mode.NUMERIC) {
        for (c in text) if (c < '0' || c > '9') throw IllegalArgumentException("Numeric mode takes digits only")
        return Mode.NUMERIC
    }
    if (requested == Mode.ALPHANUMERIC) {
        for (c in text) if (ALPHANUMERIC.indexOf(c) < 0) throw IllegalArgumentException("Not in the alphanumeric set: $c")
        return Mode.ALPHANUMERIC
    }
    if (requested == Mode.BYTE) return Mode.BYTE
    var numeric = true
    for (c in text) {
        if (c in '0'..'9') continue
        if (ALPHANUMERIC.indexOf(c) < 0) return Mode.BYTE
        numeric = false
    }
    return if (text.isNotEmpty() && numeric) Mode.NUMERIC else if (text.isNotEmpty()) Mode.ALPHANUMERIC else Mode.BYTE
}

private class SegmentPayload(
    /** Characters for numeric and alphanumeric, bytes for byte mode. */
    val count: Int,
    val bits: List<Int>,
)

private fun segmentBits(text: String?, bytes: ByteArray?, mode: Mode): SegmentPayload {
    val bits = ArrayList<Int>()
    if (mode == Mode.BYTE) {
        val data = bytes ?: utf8(text!!)
        for (b in data) push(bits, b.toInt() and 0xff, 8)
        return SegmentPayload(data.size, bits)
    }
    val t = text!!
    if (mode == Mode.NUMERIC) {
        var i = 0
        while (i < t.length) {
            val group = t.substring(i, minOf(i + 3, t.length))
            push(bits, group.toInt(), group.length * 3 + 1)
            i += 3
        }
    } else {
        var i = 0
        while (i < t.length) {
            val a = ALPHANUMERIC.indexOf(t[i])
            if (i + 1 < t.length) push(bits, a * 45 + ALPHANUMERIC.indexOf(t[i + 1]), 11)
            else push(bits, a, 6)
            i += 2
        }
    }
    return SegmentPayload(t.length, bits)
}

/** UTF-8 bytes of [text], with lone surrogates as U+FFFD (as JavaScript's TextEncoder does). */
internal fun utf8(text: String): ByteArray {
    val out = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        var c = text[i].code
        if (c in 0xd800..0xdbff && i + 1 < text.length) {
            val d = text[i + 1].code
            if (d in 0xdc00..0xdfff) {
                c = 0x10000 + ((c - 0xd800) shl 10) + (d - 0xdc00)
                i++
            }
        }
        if (c in 0xd800..0xdfff) c = 0xfffd
        if (c < 0x80) {
            out.add(c.toByte())
        } else if (c < 0x800) {
            out.add((0xc0 or (c shr 6)).toByte())
            out.add((0x80 or (c and 63)).toByte())
        } else if (c < 0x10000) {
            out.add((0xe0 or (c shr 12)).toByte())
            out.add((0x80 or ((c shr 6) and 63)).toByte())
            out.add((0x80 or (c and 63)).toByte())
        } else {
            out.add((0xf0 or (c shr 18)).toByte())
            out.add((0x80 or ((c shr 12) and 63)).toByte())
            out.add((0x80 or ((c shr 6) and 63)).toByte())
            out.add((0x80 or (c and 63)).toByte())
        }
        i++
    }
    return out.toByteArray()
}

private fun chooseVersion(mode: Mode, payload: SegmentPayload, eci: Boolean, ecc: ErrorCorrection, minVersion: Int): Int {
    for (v in minVersion..40) {
        val countBits = countBitsFor(mode, countBand(v))
        if (payload.count >= (1 shl countBits)) continue
        if (headerLength(countBits, eci) + payload.bits.size <= dataCodewords(v, ecc) * 8) return v
    }
    throw QrCapacityError("Data does not fit any version at level $ecc")
}

/** [EC codewords per block, group 1 blocks, group 1 data codewords per block, group 2 blocks]. */
internal fun blocksFor(version: Int, ecc: ErrorCorrection): IntArray = EC_BLOCKS[version - 1][EC_ORDER.indexOf(ecc)]

private fun dataCodewords(version: Int, ecc: ErrorCorrection): Int {
    val b = blocksFor(version, ecc)
    return b[1] * b[2] + b[3] * (b[2] + 1)
}

private fun push(bits: MutableList<Int>, value: Int, length: Int) {
    for (i in length - 1 downTo 0) bits.add((value ushr i) and 1)
}

// Reed-Solomon over GF(256), primitive polynomial x^8 + x^4 + x^3 + x^2 + 1.
internal val EXP = IntArray(512)
internal val LOG = IntArray(256)

@Suppress("unused")
private val gfReady: Boolean = run {
    var x = 1
    for (i in 0 until 255) {
        EXP[i] = x
        LOG[x] = i
        x = x shl 1
        if (x and 0x100 != 0) x = x xor 0x11d
    }
    for (i in 255 until 512) EXP[i] = EXP[i - 255]
    true
}

internal fun gfMul(a: Int, b: Int): Int {
    return if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
}

private val generators = HashMap<Int, IntArray>()

/** Monic generator polynomial of the given degree, highest coefficient first. */
private fun generator(degree: Int): IntArray {
    generators[degree]?.let { return it }
    var poly = intArrayOf(1)
    for (i in 0 until degree) {
        val next = IntArray(poly.size + 1)
        for (j in poly.indices) {
            next[j] = next[j] xor poly[j]
            next[j + 1] = next[j + 1] xor gfMul(poly[j], EXP[i])
        }
        poly = next
    }
    generators[degree] = poly
    return poly
}

/** Check codewords of [data] (values 0..255) for a block with [degree] check codewords. */
private fun remainder(data: IntArray, degree: Int): IntArray {
    val gen = generator(degree)
    val out = IntArray(degree)
    for (byte in data) {
        val factor = byte xor out[0]
        System.arraycopy(out, 1, out, 0, degree - 1)
        out[degree - 1] = 0
        for (i in 0 until degree) out[i] = out[i] xor gfMul(gen[i + 1], factor)
    }
    return out
}

private fun interleave(codewords: IntArray, version: Int, ecc: ErrorCorrection): IntArray {
    val b = blocksFor(version, ecc)
    val ecPerBlock = b[0]
    val g1 = b[1]
    val d1 = b[2]
    val g2 = b[3]
    val data = ArrayList<IntArray>()
    val checks = ArrayList<IntArray>()
    var offset = 0
    for (blk in 0 until g1 + g2) {
        val length = if (blk < g1) d1 else d1 + 1
        val block = codewords.copyOfRange(offset, offset + length)
        offset += length
        data.add(block)
        checks.add(remainder(block, ecPerBlock))
    }
    val out = IntArray(codewords.size + (g1 + g2) * ecPerBlock)
    var k = 0
    for (i in 0..d1) for (block in data) if (i < block.size) out[k++] = block[i]
    for (i in 0 until ecPerBlock) for (block in checks) out[k++] = block[i]
    return out
}

/** Format information coordinates (x, y) for bits 0 to 14, beside the top-left finder. */
internal val FORMAT_COORDS: Array<IntArray> = arrayOf(
    intArrayOf(8, 0), intArrayOf(8, 1), intArrayOf(8, 2), intArrayOf(8, 3), intArrayOf(8, 4), intArrayOf(8, 5),
    intArrayOf(8, 7), intArrayOf(8, 8), intArrayOf(7, 8), intArrayOf(5, 8), intArrayOf(4, 8), intArrayOf(3, 8),
    intArrayOf(2, 8), intArrayOf(1, 8), intArrayOf(0, 8),
)

internal class Grid(private val version: Int) {
    val size: Int = 17 + 4 * version
    private val base = ByteArray(size * size)
    private val reserved = ByteArray(size * size)

    init {
        val s = size
        finder(0, 0)
        finder(s - 7, 0)
        finder(0, s - 7)
        fn(8, s - 8, 1)
        val centres = ALIGNMENT_CENTRES[version - 1]
        for (cy in centres) {
            for (cx in centres) {
                if (reserved[cy * s + cx].toInt() != 0) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        fn(cx + dx, cy + dy, if (maxOf(abs(dx), abs(dy)) != 1) 1 else 0)
                    }
                }
            }
        }
        for (i in 8 until s - 8) {
            if (reserved[6 * s + i].toInt() == 0) fn(i, 6, if (i % 2 == 0) 1 else 0)
            if (reserved[i * s + 6].toInt() == 0) fn(6, i, if (i % 2 == 0) 1 else 0)
        }
        for (i in 0 until 15) {
            val x = FORMAT_COORDS[i][0]
            val y = FORMAT_COORDS[i][1]
            fn(x, y, 0)
            if (i < 8) fn(s - 1 - i, 8, 0)
            else fn(8, s - 7 + (i - 8), 0)
        }
        if (version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    fn(i, s - 11 + j, 0)
                    fn(s - 11 + j, i, 0)
                }
            }
        }
    }

    /** True for finder, separator, timing, alignment, format and version modules. */
    fun isReserved(x: Int, y: Int): Boolean = reserved[y * size + x].toInt() == 1

    private fun fn(x: Int, y: Int, dark: Int) {
        base[y * size + x] = dark.toByte()
        reserved[y * size + x] = 1
    }

    private fun finder(left: Int, top: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val x = left + dx
                val y = top + dy
                if (x < 0 || y < 0 || x >= size || y >= size) continue
                val ring = maxOf(abs(dx - 3), abs(dy - 3))
                fn(x, y, if (ring != 2 && ring != 4) 1 else 0)
            }
        }
    }

    /** Zigzag placement of the final codeword stream; remainder bits are light. */
    fun placeData(stream: IntArray) {
        val s = size
        val total = stream.size * 8
        var bit = 0
        var upward = true
        var right = s - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (step in 0 until s) {
                val y = if (upward) s - 1 - step else step
                for (i in 0 until 2) {
                    val x = right - i
                    if (reserved[y * s + x].toInt() != 0) continue
                    if (bit < total) base[y * s + x] = ((stream[bit ushr 3] ushr (7 - (bit and 7))) and 1).toByte()
                    bit++
                }
            }
            upward = !upward
            right -= 2
        }
    }

    /** A copy with the data masked and the format and version information drawn. */
    fun render(ecc: ErrorCorrection, mask: Int): ByteArray {
        val s = size
        val out = base.copyOf()
        for (y in 0 until s) {
            for (x in 0 until s) {
                if (reserved[y * s + x].toInt() == 0 && maskBit(mask, x, y)) out[y * s + x] = (out[y * s + x].toInt() xor 1).toByte()
            }
        }
        val format = formatBits(ecc, mask)
        for (i in 0 until 15) {
            val bit = ((format ushr i) and 1).toByte()
            val x = FORMAT_COORDS[i][0]
            val y = FORMAT_COORDS[i][1]
            out[y * s + x] = bit
            if (i < 8) out[8 * s + (s - 1 - i)] = bit
            else out[(s - 7 + (i - 8)) * s + 8] = bit
        }
        if (version >= 7) {
            val info = versionBits(version)
            for (k in 0 until 18) {
                val bit = ((info ushr k) and 1).toByte()
                val i = k / 3
                val j = k % 3
                out[(s - 11 + j) * s + i] = bit
                out[i * s + (s - 11 + j)] = bit
            }
        }
        return out
    }
}

internal fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
    0 -> (x + y) % 2 == 0
    1 -> y % 2 == 0
    2 -> x % 3 == 0
    3 -> (x + y) % 3 == 0
    4 -> (y / 2 + x / 3) % 2 == 0
    5 -> ((x * y) % 2) + ((x * y) % 3) == 0
    6 -> (((x * y) % 2) + ((x * y) % 3)) % 2 == 0
    else -> (((x + y) % 2) + ((x * y) % 3)) % 2 == 0
}

internal fun formatBits(ecc: ErrorCorrection, mask: Int): Int {
    val data = (EC_FORMAT_BITS[ecc.ordinal] shl 3) or mask
    var rem = data
    for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
    return ((data shl 10) or rem) xor 0x5412
}

internal fun versionBits(version: Int): Int {
    var rem = version
    for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1f25)
    return (version shl 12) or rem
}

/** Mask penalty per ISO/IEC 18004 section 7.8.3, scoring the finished symbol. */
internal fun penalty(modules: ByteArray, size: Int): Int {
    var score = 0
    var dark = 0
    for (y in 0 until size) {
        var rowRun = 0
        var colRun = 0
        for (x in 0 until size) {
            val here = modules[y * size + x]
            dark += here
            if (x > 0 && here == modules[y * size + x - 1]) rowRun++
            else {
                if (rowRun >= 5) score += rowRun - 2
                rowRun = 1
            }
            val down = modules[x * size + y]
            if (x > 0 && down == modules[(x - 1) * size + y]) colRun++
            else {
                if (colRun >= 5) score += colRun - 2
                colRun = 1
            }
            if (x < size - 1 && y < size - 1) {
                if (here == modules[y * size + x + 1] &&
                    here == modules[(y + 1) * size + x] &&
                    here == modules[(y + 1) * size + x + 1]
                ) score += 3
            }
            if (x + 6 < size && finderLike(modules, size, x, y, 1, 0)) score += 40
            if (x + 6 < size && finderLike(modules, size, y, x, 0, 1)) score += 40
        }
        if (rowRun >= 5) score += rowRun - 2
        if (colRun >= 5) score += colRun - 2
    }
    val total = size * size
    score += (abs(dark * 2 - total) * 10 / total) * 10
    return score
}

private val FINDER_RUN = intArrayOf(1, 0, 1, 1, 1, 0, 1)

/** 1:1:3:1:1 starting at (x, y) along (dx, dy), with four light modules on one side. */
private fun finderLike(m: ByteArray, size: Int, x: Int, y: Int, dx: Int, dy: Int): Boolean {
    for (i in 0 until 7) if (m[(y + i * dy) * size + x + i * dx].toInt() != FINDER_RUN[i]) return false
    return lightRun(m, size, x, y, dx, dy, -4) || lightRun(m, size, x, y, dx, dy, 7)
}

/**
 * Four modules from offset [from] along the line are light. A run that would
 * leave the symbol does not count: the quiet zone is not scored. ZXing scores
 * it the same way, which keeps mask choice identical to that oracle.
 */
private fun lightRun(m: ByteArray, size: Int, x: Int, y: Int, dx: Int, dy: Int, from: Int): Boolean {
    for (i in from until from + 4) {
        val px = x + i * dx
        val py = y + i * dy
        if (px < 0 || py < 0 || px >= size || py >= size) return false
        if (m[py * size + px].toInt() == 1) return false
    }
    return true
}
