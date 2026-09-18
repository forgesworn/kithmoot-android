package dev.forgesworn.kithmoot.media.effects

/**
 * I420 to packed ARGB, by hand.
 *
 * libwebrtc's `YuvHelper` goes the other way - `ABGRToI420` puts the finished
 * picture back on the wire - but there is nothing in it that unpacks a camera
 * frame into something a `Canvas` will draw, and the platform's own answers are
 * all worse than this one: `RenderScript` was deprecated in Android 12, and
 * `YuvImage` only goes as far as a JPEG, which would mean encoding and decoding
 * a whole frame thirty times a second to move some bytes.
 *
 * So: BT.601 limited range, in integers, over plain arrays. Plain arrays rather
 * than `ByteBuffer` because a per-byte read from a direct buffer crosses into
 * native code every time; the planes are bulk-copied out once and the loop then
 * stays in the JIT's reach. It runs at the working resolution - 640 wide at the
 * very most - and never at the camera's.
 */
object Yuv {

    /**
     * Fill [out] with `width * height` ARGB pixels from the three planes.
     *
     * @param out packed 0xAARRGGBB, opaque, row-major, at least `width * height` long
     */
    fun i420ToArgb(
        y: ByteArray,
        strideY: Int,
        u: ByteArray,
        strideU: Int,
        v: ByteArray,
        strideV: Int,
        out: IntArray,
        width: Int,
        height: Int,
    ) {
        require(out.size >= width * height) { "an ARGB buffer of ${out.size} is short of ${width * height}" }
        var o = 0
        for (row in 0 until height) {
            val yRow = row * strideY
            val chromaRow = (row shr 1)
            val uRow = chromaRow * strideU
            val vRow = chromaRow * strideV
            for (col in 0 until width) {
                val luma = ((y[yRow + col].toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                val chroma = col shr 1
                val cb = (u[uRow + chroma].toInt() and 0xFF) - 128
                val cr = (v[vRow + chroma].toInt() and 0xFF) - 128

                val r = (luma + 1634 * cr) shr 10
                val g = (luma - 833 * cr - 400 * cb) shr 10
                val b = (luma + 2066 * cb) shr 10

                out[o++] = -0x1000000 or
                    (clampByte(r) shl 16) or
                    (clampByte(g) shl 8) or
                    clampByte(b)
            }
        }
    }

    private fun clampByte(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value
}
