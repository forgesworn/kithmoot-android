package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import java.util.Base64
import java.util.zip.CRC32
import kotlinx.serialization.json.*

/** A private inline logo. Image decoding remains a separate local renderer check. */
class LogoImage internal constructor(
    val mime: String, val width: Int, val height: Int, val data: String, val sha256: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1); put("mime", mime); put("width", width); put("height", height)
        put("data", data); put("sha256", sha256)
    }
}

/** Matches the bounded still-image wire format; no URL or metadata repair is accepted. */
object LogoImages {
    const val MAX_BYTES = 16_384
    const val MAX_PIXELS = 256
    private val fields = setOf("v", "mime", "width", "height", "data", "sha256")
    private val hash = Regex("[0-9a-f]{64}")
    private fun number(value: JsonElement?, expected: Int) = (value as? JsonPrimitive)?.let {
        !it.isString && it.doubleOrNull == expected.toDouble()
    } == true
    private fun square(width: Long, height: Long) = width in 1..MAX_PIXELS.toLong() && width == height
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun u16(b: ByteArray, i: Int) = u8(b, i) + (u8(b, i + 1) shl 8)
    private fun u24(b: ByteArray, i: Int) = (u16(b, i) + (u8(b, i + 2) shl 16)).toLong()
    private fun u32(b: ByteArray, i: Int, little: Boolean = false): Long =
        (0..3).fold(0L) { value, n -> value or (u8(b, i + n).toLong() shl (8 * if (little) n else 3 - n)) }
    private fun ascii(b: ByteArray, i: Int, size: Int) = String(b, i, size, Charsets.ISO_8859_1)

    fun create(bytes: ByteArray, mime: String): LogoImage {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Logo exceeds the 16 KiB limit" }
        val size = when (mime) {
            "image/png" -> pngSize(bytes)
            "image/webp" -> webpSize(bytes)
            else -> null
        }
        requireNotNull(size) { "Use a small square PNG or WebP without animation or embedded metadata" }
        return LogoImage(mime, size.first.toInt(), size.second.toInt(),
            Base64.getEncoder().encodeToString(bytes), Digests.sha256(bytes).toHex())
    }

    fun read(value: JsonElement?): LogoImage? = try {
        val p = value as? JsonObject
        if (p == null || p.keys != fields || !number(p["v"], 1)) null else {
            val mime = p["mime"] as? JsonPrimitive
            val data = p["data"] as? JsonPrimitive
            val sha = p["sha256"] as? JsonPrimitive
            if (mime?.isString != true || data?.isString != true || sha?.isString != true ||
                data.content.length > ((MAX_BYTES + 2) / 3) * 4 || !hash.matches(sha.content)) null else {
                val bytes = Base64.getDecoder().decode(data.content)
                if (Base64.getEncoder().encodeToString(bytes) != data.content) null else {
                    val checked = create(bytes, mime.content)
                    if (checked.sha256 == sha.content && number(p["width"], checked.width) &&
                        number(p["height"], checked.height)) checked else null
                }
            }
        }
    } catch (_: IllegalArgumentException) { null }

    /** Only validated bytes may be handed to an Android or desktop image decoder. */
    fun bytes(value: JsonElement?): ByteArray? = read(value)?.let { Base64.getDecoder().decode(it.data) }

    private fun pngSize(b: ByteArray): Pair<Long, Long>? {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (b.size < 45 || !b.copyOfRange(0, 8).contentEquals(signature)) return null
        var offset = 8; var size: Pair<Long, Long>? = null; var image = false; var imageEnded = false
        val seen = mutableSetOf<String>()
        while (offset + 12 <= b.size) {
            val length = u32(b, offset); val type = ascii(b, offset + 4, 4)
            val endLong = offset + 12L + length
            if (endLong > b.size || type !in setOf("IHDR", "PLTE", "tRNS", "sRGB", "gAMA", "cHRM", "IDAT", "IEND")) return null
            val end = endLong.toInt()
            val crc = CRC32().apply { update(b, offset + 4, end - offset - 8) }.value
            if (crc != u32(b, end - 4) || type != "IDAT" && type in seen || size == null && type != "IHDR") return null
            when (type) {
                "IHDR" -> {
                    if (length != 13L || offset != 8) return null
                    val width = u32(b, offset + 8); val height = u32(b, offset + 12)
                    if (!square(width, height) || u8(b, offset + 18) != 0 || u8(b, offset + 19) != 0 || u8(b, offset + 20) > 1) return null
                    size = width to height
                }
                "IDAT" -> { if (length == 0L || imageEnded) return null; image = true }
                "IEND" -> return if (length == 0L && image && end == b.size) size else null
                else -> {
                    if (image) imageEnded = true
                    if (image || type == "PLTE" && (length == 0L || length > 768 || length % 3 != 0L) ||
                        type == "sRGB" && length != 1L || type == "gAMA" && length != 4L ||
                        type == "cHRM" && length != 32L || type == "tRNS" && length > 256) return null
                }
            }
            seen.add(type); offset = end
        }
        return null
    }

    private fun webpSize(b: ByteArray): Pair<Long, Long>? {
        if (b.size < 26 || ascii(b, 0, 4) != "RIFF" || ascii(b, 8, 4) != "WEBP" || u32(b, 4, true) + 8 != b.size.toLong()) return null
        var offset = 12; var canvas: Pair<Long, Long>? = null; var frame: Pair<Long, Long>? = null
        var alpha = false; var alphaChunk = false; val seen = mutableSetOf<String>()
        while (offset + 8 <= b.size) {
            val type = ascii(b, offset, 4); val length = u32(b, offset + 4, true); val data = offset + 8
            val endLong = data + length; val padded = endLong + length % 2
            if (padded > b.size || length % 2 != 0L && u8(b, endLong.toInt()) != 0 || type in seen || frame != null ||
                type !in setOf("VP8X", "ALPH", "VP8 ", "VP8L")) return null
            when (type) {
                "VP8X" -> {
                    if (offset != 12 || length != 10L || u8(b, data) and 0xEF != 0 ||
                        u8(b, data + 1) != 0 || u8(b, data + 2) != 0 || u8(b, data + 3) != 0) return null
                    canvas = (u24(b, data + 4) + 1) to (u24(b, data + 7) + 1)
                    if (!square(canvas.first, canvas.second)) return null
                    alpha = u8(b, data) and 16 != 0
                }
                "ALPH" -> { if (canvas == null || !alpha || length < 2) return null; alphaChunk = true }
                "VP8 " -> {
                    if (length < 10 || u8(b, data) and 1 != 0 || ascii(b, data + 3, 3) != "\u009d\u0001*" || alpha && !alphaChunk) return null
                    frame = (u16(b, data + 6) and 0x3FFF).toLong() to (u16(b, data + 8) and 0x3FFF).toLong()
                }
                "VP8L" -> {
                    if (length < 5 || u8(b, data) != 47 || alphaChunk) return null
                    val dimensions = u32(b, data + 1, true)
                    if (dimensions ushr 29 != 0L) return null
                    frame = ((dimensions and 0x3FFF) + 1) to ((dimensions ushr 14 and 0x3FFF) + 1)
                }
            }
            seen.add(type); offset = padded.toInt()
        }
        return frame?.takeIf { offset == b.size && square(it.first, it.second) && (canvas == null || canvas == it) }
    }
}
