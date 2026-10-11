package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.LogoImage
import dev.forgesworn.kithmoot.protocol.LogoImages
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Local picker input only. These helpers never repair an incoming signed logo. */
internal object LogoPixels {
    const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    const val MAX_SOURCE_SIDE = 8192
    const val MAX_SOURCE_PIXELS = 16_000_000L
    private fun ascii(b: ByteArray, at: Int, count: Int) = String(b, at, count, Charsets.ISO_8859_1)
    private fun uint(b: ByteArray, at: Int, little: Boolean = false): Long = (0..3).fold(0L) { v, i ->
        v or ((b[at + i].toLong() and 255) shl (8 * if (little) i else 3 - i))
    }

    fun readSource(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_SOURCE_BYTES) { "Choose an image smaller than 8 MiB." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray().also(::checkStillSource)
    }

    fun checkDimensions(width: Int, height: Int) {
        require(width in 1..MAX_SOURCE_SIDE && height in 1..MAX_SOURCE_SIDE && width.toLong() * height <= MAX_SOURCE_PIXELS) {
            "Choose an image up to 16 megapixels and 8192 pixels per side."
        }
    }

    /** Do not silently turn an animated upload into its first frame. */
    fun checkStillSource(b: ByteArray) {
        require(b.isNotEmpty() && b.size <= MAX_SOURCE_BYTES) { "Choose an image smaller than 8 MiB." }
        if (b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()) return
        if (b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) {
            var at = 8
            while (at + 12 <= b.size) {
                val end = at + 12L + uint(b, at)
                require(end <= b.size) { "This PNG is incomplete." }
                val type = ascii(b, at + 4, 4)
                require(type !in setOf("acTL", "fcTL", "fdAT")) { "Choose a still image rather than an animation." }
                at = end.toInt()
                if (type == "IEND") { require(at == b.size); return }
            }
            error("This PNG is incomplete.")
        }
        if (b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP") {
            require(uint(b, 4, true) + 8 == b.size.toLong()) { "This WebP is incomplete." }
            var at = 12
            while (at + 8 <= b.size) {
                val type = ascii(b, at, 4); val length = uint(b, at + 4, true)
                val end = at + 8L + length + length % 2
                require(end <= b.size) { "This WebP is incomplete." }
                require(type !in setOf("ANIM", "ANMF") && (type != "VP8X" ||
                    length == 10L && b[at + 8].toInt() and 2 == 0)) { "Choose a still image rather than an animation." }
                at = end.toInt()
            }
            require(at == b.size); return
        }
        error("Choose a PNG, JPEG or still WebP image.")
    }

    data class Crop(val left: Float, val top: Float, val side: Float)
    fun crop(width: Int, height: Int, zoom: Float, x: Float, y: Float): Crop {
        require(width > 0 && height > 0 && zoom.isFinite() && x.isFinite() && y.isFinite())
        val side = minOf(width, height) / zoom.coerceIn(1f, 4f)
        return Crop((width - side) * x.coerceIn(0f, 1f), (height - side) * y.coerceIn(0f, 1f), side)
    }

    /** Only call with fresh, square, sRGB pixels encoded by our own canvas. */
    fun fromFreshEncoding(b: ByteArray, mime: String): LogoImage {
        require(b.size in 1..524_288)
        val out = ByteArrayOutputStream()
        when (mime) {
            "image/png" -> {
                require(b.size >= 8)
                out.write(b, 0, 8); var at = 8
                val kept = setOf("IHDR", "PLTE", "tRNS", "sRGB", "gAMA", "cHRM", "IDAT", "IEND")
                while (at + 12 <= b.size) {
                    val end = at + 12L + uint(b, at)
                    require(end <= b.size)
                    val type = ascii(b, at + 4, 4)
                    require(type !in setOf("acTL", "fcTL", "fdAT"))
                    if (type in kept) out.write(b, at, end.toInt() - at)
                    else require(b[at + 4].toInt() and 32 != 0) { "Unexpected PNG data." }
                    at = end.toInt()
                }
                require(at == b.size)
            }
            "image/webp" -> {
                require(b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" && uint(b, 4, true) + 8 == b.size.toLong())
                out.write(b, 0, 12); var at = 12
                while (at + 8 <= b.size) {
                    val type = ascii(b, at, 4); val length = uint(b, at + 4, true)
                    val end = at + 8L + length + length % 2
                    require(end <= b.size)
                    if (type in setOf("VP8X", "ALPH", "VP8 ", "VP8L")) {
                        val chunk = b.copyOfRange(at, end.toInt())
                        if (type == "VP8X") {
                            require(length == 10L && chunk[8].toInt() and 2 == 0)
                            chunk[8] = (chunk[8].toInt() and 16).toByte()
                        }
                        out.write(chunk)
                    } else require(type in setOf("ICCP", "EXIF", "XMP ")) { "Unexpected WebP data." }
                    at = end.toInt()
                }
                require(at == b.size)
                val cleaned = out.toByteArray(); val size = cleaned.size - 8
                for (i in 0..3) cleaned[4 + i] = (size ushr (8 * i)).toByte()
                return LogoImages.create(cleaned, mime)
            }
            else -> error("Unsupported canvas encoding.")
        }
        return LogoImages.create(out.toByteArray(), mime)
    }
}
