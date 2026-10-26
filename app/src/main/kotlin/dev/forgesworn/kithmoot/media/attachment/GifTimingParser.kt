package dev.forgesworn.kithmoot.media.attachment

internal data class GifTiming(
    val width: Int,
    val height: Int,
    val seekDelaysMs: List<Int>,
    val delaysMs: List<Int>,
)

/** Walks every GIF block so Movie never receives an unchecked block stream. */
internal object GifTimingParser {
    fun parse(bytes: ByteArray): GifTiming {
        require(bytes.size <= MAX_ATTACHMENT_PLAINTEXT_BYTES) { "GIF exceeds 32 MiB." }
        val input = Input(bytes)
        val signature = input.take(6).toString(Charsets.US_ASCII)
        require(signature == "GIF87a" || signature == "GIF89a") { "Invalid GIF signature." }
        val width = input.u16()
        val height = input.u16()
        checkedImageGeometry(width, height, 1)
        val packed = input.u8()
        input.skip(2)
        if (packed and 0x80 != 0) input.skip(3 * input.tableSize(packed))
        val seekDelays = mutableListOf<Int>()
        val displayDelays = mutableListOf<Int>()
        var pendingDelay = 0
        var trailer = false
        while (input.remaining > 0) {
            when (val introducer = input.u8()) {
                0x21 -> {
                    when (input.u8()) {
                        0xf9 -> {
                            require(input.u8() == 4) { "Invalid GIF graphics control block." }
                            input.skip(1)
                            pendingDelay = input.u16()
                            input.skip(1)
                            require(input.u8() == 0) { "Invalid GIF graphics control terminator." }
                        }
                        0xff -> {
                            input.skipSubBlocks()
                        }
                        else -> input.skipSubBlocks()
                    }
                }
                0x2c -> {
                    input.skip(8)
                    val imagePacked = input.u8()
                    if (imagePacked and 0x80 != 0) input.skip(3 * input.tableSize(imagePacked))
                    input.skip(1)
                    input.skipSubBlocks()
                    require(pendingDelay != 0) { "GIF frames with zero raw delay are unsupported." }
                    if (seekDelays.size >= MAX_IMAGE_FRAMES) throw AttachmentLimitException("GIF has more than 256 frames.")
                    seekDelays += pendingDelay * 10
                    displayDelays += gifDelayMillis(pendingDelay)
                    pendingDelay = 0
                    checkedImageGeometry(width, height, seekDelays.size)
                }
                0x3b -> {
                    trailer = true
                    break
                }
                else -> error("Invalid GIF block 0x${introducer.toString(16)}.")
            }
        }
        require(trailer && input.remaining == 0 && seekDelays.isNotEmpty()) { "Incomplete GIF." }
        return GifTiming(width, height, seekDelays, displayDelays)
    }

    private class Input(val bytes: ByteArray) {
        var at = 0
        val remaining get() = bytes.size - at
        fun u8(): Int {
            require(at < bytes.size) { "Truncated GIF." }
            return bytes[at++].toInt() and 0xff
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun take(count: Int): ByteArray {
            require(count >= 0 && remaining >= count) { "Truncated GIF." }
            return bytes.copyOfRange(at, at + count).also { at += count }
        }
        fun skip(count: Int) {
            require(count >= 0 && remaining >= count) { "Truncated GIF." }
            at += count
        }
        fun tableSize(packed: Int): Int = 1 shl ((packed and 7) + 1)
        fun skipSubBlocks() {
            while (true) {
                val size = u8()
                if (size == 0) return
                skip(size)
            }
        }
    }
}
