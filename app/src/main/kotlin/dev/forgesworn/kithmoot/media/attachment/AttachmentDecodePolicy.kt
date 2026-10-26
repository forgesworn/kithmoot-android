package dev.forgesworn.kithmoot.media.attachment

const val MAX_ATTACHMENT_PLAINTEXT_BYTES = 32 * 1024 * 1024
const val MAX_IMAGE_DIMENSION = 8192
const val MAX_IMAGE_PIXELS = 16_000_000L
const val MAX_IMAGE_FRAMES = 256
const val MAX_IMAGE_RGBA_BYTES = 64 * 1024 * 1024

internal val supportedAttachmentMimes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

internal data class ImageGeometry(val width: Int, val height: Int, val frames: Int, val rgbaBytes: Int)
internal class AttachmentLimitException(message: String) : IllegalArgumentException(message)

internal fun checkedImageGeometry(width: Int, height: Int, frames: Int): ImageGeometry {
    if (width !in 1..MAX_IMAGE_DIMENSION || height !in 1..MAX_IMAGE_DIMENSION) throw AttachmentLimitException("Image dimensions exceed the viewer limit.")
    if (width.toLong() * height.toLong() > MAX_IMAGE_PIXELS) throw AttachmentLimitException("Image exceeds 16 megapixels.")
    if (frames !in 1..MAX_IMAGE_FRAMES) throw AttachmentLimitException("Image has too many frames.")
    val frameBytes = Math.multiplyExact(width.toLong(), height.toLong())
        .let { Math.multiplyExact(it, 4L) }
    val total = Math.multiplyExact(frameBytes, frames.toLong())
    if (total > MAX_IMAGE_RGBA_BYTES) throw AttachmentLimitException("Decoded image exceeds 64 MiB.")
    return ImageGeometry(width, height, frames, total.toInt())
}

internal fun validateDecodedLayout(width: Int, height: Int, offsets: IntArray, delays: IntArray, size: Int, mime: String) {
    val geometry = checkedImageGeometry(width, height, offsets.size)
    require(delays.size == offsets.size && size == geometry.rgbaBytes) { "Invalid decoded image layout." }
    require(mime in supportedAttachmentMimes) { "Unsupported decoded image type." }
    val frameBytes = geometry.rgbaBytes / offsets.size
    offsets.forEachIndexed { index, offset ->
        require(offset == index * frameBytes && offset >= 0) { "Invalid decoded frame offset." }
        val delay = delays[index]
        require(if (mime == "image/gif") delay in 10..655_350 else delay == 0) { "Invalid decoded frame delay." }
    }
}

internal fun gifDelayMillis(hundredths: Int): Int {
    require(hundredths in 0..65_535)
    return if (hundredths <= 1) 100 else hundredths * 10
}
