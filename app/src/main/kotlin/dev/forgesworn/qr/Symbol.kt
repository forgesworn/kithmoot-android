package dev.forgesworn.qr

/**
 * What a symbol says, once read.
 *
 * @property text The decoded text.
 * @property bytes The payload as bytes: byte-mode data as stored, numeric and alphanumeric as ASCII.
 * @property eci ECI assignment numbers in the order they appeared; empty if none.
 * @property corrected Codewords Reed-Solomon had to correct.
 */
internal class Payload(
    val text: String,
    val bytes: ByteArray,
    val version: Int,
    val ecc: ErrorCorrection,
    val mask: Int,
    val eci: List<Int>,
    val corrected: Int,
)

/** Reads modules through a callback: true for dark, x across, y down. */
internal fun interface ModuleReader {
    fun read(x: Int, y: Int): Boolean
}

private class FormatEntry(val bits: Int, val ecc: ErrorCorrection, val mask: Int)

private class VersionEntry(val bits: Int, val version: Int)

private val FORMATS: List<FormatEntry> = EC_ORDER.flatMap { ecc ->
    (0..7).map { mask -> FormatEntry(formatBits(ecc, mask), ecc, mask) }
}
private val VERSIONS: List<VersionEntry> = (0 until 34).map { i -> VersionEntry(versionBits(i + 7), i + 7) }

private fun popcount(value: Int): Int {
    var n = value
    var c = 0
    while (n != 0) {
        n = n and (n - 1)
        c++
    }
    return c
}

private fun <T> nearest(table: List<T>, bitsOf: (T) -> Int, reads: IntArray): T? {
    var best: T? = null
    var distance = 4 // both codes correct up to three bit errors
    for (read in reads) {
        for (entry in table) {
            val d = popcount(bitsOf(entry) xor read)
            if (d < distance) {
                distance = d
                best = entry
            }
        }
    }
    return best
}

/** The version a symbol of [size] modules declares, when its version blocks can be read. */
internal fun readVersion(read: ModuleReader, size: Int): Int? {
    if (size < 45) return (size - 17) / 4
    var bottomLeft = 0
    var topRight = 0
    for (k in 17 downTo 0) {
        val i = k / 3
        val j = k % 3
        bottomLeft = (bottomLeft shl 1) or (if (read.read(i, size - 11 + j)) 1 else 0)
        topRight = (topRight shl 1) or (if (read.read(size - 11 + j, i)) 1 else 0)
    }
    return nearest(VERSIONS, { it.bits }, intArrayOf(bottomLeft, topRight))?.version
}

/**
 * Decodes a symbol from its modules. [size] must be 17 + 4v; for version 7
 * and up the version blocks decide, and a size that disagrees fails.
 * Returns null when the symbol cannot be read.
 */
internal fun readSymbol(read: ModuleReader, size: Int): Payload? {
    if (size < 21 || size > 177 || (size - 17) % 4 != 0) return null
    val version = readVersion(read, size)
    if (version == null || 17 + 4 * version != size) return null

    var first = 0
    var second = 0
    for (i in 14 downTo 0) {
        val x = FORMAT_COORDS[i][0]
        val y = FORMAT_COORDS[i][1]
        first = (first shl 1) or (if (read.read(x, y)) 1 else 0)
        second = (second shl 1) or
            (if (read.read(if (i < 8) size - 1 - i else 8, if (i < 8) 8 else size - 7 + (i - 8))) 1 else 0)
    }
    val format = nearest(FORMATS, { it.bits }, intArrayOf(first, second)) ?: return null

    val grid = Grid(version)
    val blocksInfo = blocksFor(version, format.ecc)
    val ecPerBlock = blocksInfo[0]
    val g1 = blocksInfo[1]
    val d1 = blocksInfo[2]
    val g2 = blocksInfo[3]
    val blockCount = g1 + g2
    val total = g1 * d1 + g2 * (d1 + 1) + blockCount * ecPerBlock
    val stream = IntArray(total)
    var bit = 0
    var upward = true
    var right = size - 1
    while (right >= 1 && bit < total * 8) {
        if (right == 6) right = 5
        for (step in 0 until size) {
            val y = if (upward) size - 1 - step else step
            for (i in 0 until 2) {
                val x = right - i
                if (grid.isReserved(x, y) || bit >= total * 8) continue
                if (read.read(x, y) != maskBit(format.mask, x, y)) stream[bit ushr 3] = stream[bit ushr 3] or (0x80 ushr (bit and 7))
                bit++
            }
        }
        upward = !upward
        right -= 2
    }

    val blocks = ArrayList<IntArray>()
    for (b in 0 until blockCount) blocks.add(IntArray((if (b < g1) d1 else d1 + 1) + ecPerBlock))
    var k = 0
    for (i in 0..d1) {
        for (b in 0 until blockCount) if (i < (if (b < g1) d1 else d1 + 1)) blocks[b][i] = stream[k++]
    }
    for (i in 0 until ecPerBlock) {
        for (b in 0 until blockCount) blocks[b][(if (b < g1) d1 else d1 + 1) + i] = stream[k++]
    }
    val data = IntArray(g1 * d1 + g2 * (d1 + 1))
    var corrected = 0
    var offset = 0
    for (b in 0 until blockCount) {
        val fixed = correct(blocks[b], ecPerBlock)
        if (fixed < 0) return null
        corrected += fixed
        val length = if (b < g1) d1 else d1 + 1
        System.arraycopy(blocks[b], 0, data, offset, length)
        offset += length
    }

    val parsed = parse(data, version) ?: return null
    return Payload(parsed.text, parsed.bytes, version, format.ecc, format.mask, parsed.eci, corrected)
}

/** Thrown inside [parse] when the bit stream ends early; never leaves this file. */
private class RanOff : RuntimeException("Ran off the end of the data", null, false, false)

private class Bits(private val data: IntArray) {
    private var position = 0

    val available: Int get() = data.size * 8 - position

    fun read(count: Int): Int {
        if (count > available) throw RanOff()
        var value = 0
        for (i in 0 until count) {
            value = (value shl 1) or ((data[position ushr 3] ushr (7 - (position and 7))) and 1)
            position++
        }
        return value
    }
}

private class Run(val eci: Int?) {
    val bytes = ArrayList<Int>()
}

private class Parsed(val text: String, val bytes: ByteArray, val eci: List<Int>)

private val KANJI_COUNT_BITS = intArrayOf(8, 10, 12)
private val NUMERIC_COUNT_BITS = intArrayOf(10, 12, 14)
private val ALPHANUMERIC_COUNT_BITS = intArrayOf(9, 11, 13)
private val BYTE_COUNT_BITS = intArrayOf(8, 16, 16)

private fun parse(data: IntArray, version: Int): Parsed? {
    val bits = Bits(data)
    val band = countBand(version)
    val runs = ArrayList<Run>()
    runs.add(Run(null))
    val eci = ArrayList<Int>()
    val out = ArrayList<Int>()
    fun emit(byte: Int) {
        runs[runs.size - 1].bytes.add(byte)
        out.add(byte)
    }
    try {
        while (bits.available >= 4) {
            val mode = bits.read(4)
            if (mode == 0) break
            if (mode == 1) {
                var count = bits.read(NUMERIC_COUNT_BITS[band])
                while (count >= 3) {
                    val v = bits.read(10)
                    if (v > 999) return null
                    for (c in v.toString().padStart(3, '0')) emit(c.code)
                    count -= 3
                }
                if (count > 0) {
                    val v = bits.read(if (count == 2) 7 else 4)
                    if (v >= (if (count == 2) 100 else 10)) return null
                    for (c in v.toString().padStart(count, '0')) emit(c.code)
                }
            } else if (mode == 2) {
                var count = bits.read(ALPHANUMERIC_COUNT_BITS[band])
                while (count >= 2) {
                    val v = bits.read(11)
                    if (v >= 45 * 45) return null
                    emit(ALPHANUMERIC[v / 45].code)
                    emit(ALPHANUMERIC[v % 45].code)
                    count -= 2
                }
                if (count == 1) {
                    val v = bits.read(6)
                    if (v >= 45) return null
                    emit(ALPHANUMERIC[v].code)
                }
            } else if (mode == 4) {
                val count = bits.read(BYTE_COUNT_BITS[band])
                for (i in 0 until count) emit(bits.read(8))
            } else if (mode == 8) {
                // Kanji: kept as its Shift_JIS bytes; the text shows U+FFFD for each.
                val count = bits.read(KANJI_COUNT_BITS[band])
                for (i in 0 until count) {
                    val v = bits.read(13)
                    var sjis = ((v / 0xc0) shl 8) or (v % 0xc0)
                    sjis += if (sjis < 0x1f00) 0x8140 else 0xc140
                    out.add(sjis ushr 8)
                    out.add(sjis and 0xff)
                    val before = runs[runs.size - 1].eci
                    runs.add(Run(-1))
                    runs.add(Run(before))
                }
            } else if (mode == 7) {
                val first = bits.read(8)
                val value =
                    if ((first and 0x80) == 0) first
                    else if ((first and 0xc0) == 0x80) ((first and 0x3f) shl 8) or bits.read(8)
                    else if ((first and 0xe0) == 0xc0) ((first and 0x1f) shl 16) or bits.read(16)
                    else -1
                if (value < 0) return null
                eci.add(value)
                runs.add(Run(value))
            } else if (mode == 3) {
                bits.read(16) // structured append: position, total and parity; one symbol is read alone
            } else if (mode == 5) {
                // FNC1 in first position: GS1 data follows in ordinary segments
            } else if (mode == 9) {
                bits.read(8) // FNC1 in second position: application indicator
            } else {
                return null
            }
        }
    } catch (e: RanOff) {
        return null
    }
    val text = StringBuilder()
    for (run in runs) text.append(if (run.eci == -1) "�" else decodeRun(run))
    return Parsed(text.toString(), ByteArray(out.size) { out[it].toByte() }, eci)
}

/** ECI 26 is UTF-8; 3 and 1 are Latin-1. Without ECI, valid UTF-8 is taken as UTF-8, else Latin-1. */
private fun decodeRun(run: Run): String {
    val bytes = run.bytes.toIntArray()
    if (run.eci == 3 || run.eci == 1) return latin1(bytes)
    if (run.eci == 26 || isUtf8(bytes)) return fromUtf8(bytes)
    return latin1(bytes)
}

private fun latin1(bytes: IntArray): String {
    val s = StringBuilder()
    for (b in bytes) s.append(b.toChar())
    return s.toString()
}
