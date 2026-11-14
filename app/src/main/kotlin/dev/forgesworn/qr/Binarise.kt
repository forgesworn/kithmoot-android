package dev.forgesworn.qr

/** A greyscale picture: one byte per pixel, 0 black to 255 white (stored as Int, 0..255). */
internal class Luminance(val data: IntArray, val width: Int, val height: Int)

/** A black-and-white picture: 1 for dark. */
internal class BitImage(val data: ByteArray, val width: Int, val height: Int)

/**
 * Greyscale from a Y plane (one byte a pixel), with an optional row stride in
 * pixels for padded camera planes.
 */
internal fun toLuminance(data: ByteArray, width: Int, height: Int, rowStride: Int): Luminance {
    val out = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            out[y * width + x] = data[y * rowStride + x].toInt() and 0xff
        }
    }
    return Luminance(out, width, height)
}

/**
 * Greyscale from packed ARGB pixels (Android's `Bitmap.getPixels` layout), with
 * an optional row stride in pixels. Transparent pixels count as white, so a
 * code drawn on a transparent canvas still reads. The grey is
 * `(77 R + 150 G + 29 B) >> 8`, blended onto white by alpha.
 */
internal fun toLuminance(argb: IntArray, width: Int, height: Int, rowStride: Int): Luminance {
    val out = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val p = argb[y * rowStride + x]
            val a = (p ushr 24) and 0xff
            val r = (p ushr 16) and 0xff
            val g = (p ushr 8) and 0xff
            val b = p and 0xff
            val grey = (r * 77 + g * 150 + b * 29) shr 8
            out[y * width + x] = (grey * a + 255 * (255 - a)) / 255
        }
    }
    return Luminance(out, width, height)
}

private const val BLOCK = 8
private const val MIN_DYNAMIC_RANGE = 24

/**
 * Local-threshold binarisation: each 8x8 block's threshold is the mean of the
 * block averages in the 5x5 blocks around it, and a flat block borrows its
 * neighbours' threshold, so shadows and gradients across the frame do not
 * swallow the code. Pictures under 40 pixels a side use one global threshold.
 */
internal fun binarise(image: Luminance): BitImage {
    val data = image.data
    val width = image.width
    val height = image.height
    val out = ByteArray(width * height)
    if (width < 40 || height < 40) {
        var min = 255
        var max = 0
        for (v in data) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val threshold: Double = if (max - min < MIN_DYNAMIC_RANGE) -1.0 else (min + max) / 2.0
        for (i in data.indices) out[i] = if (data[i] <= threshold) 1 else 0
        return BitImage(out, width, height)
    }
    val across = (width + BLOCK - 1) / BLOCK
    val down = (height + BLOCK - 1) / BLOCK
    val levels = DoubleArray(across * down)
    for (by in 0 until down) {
        val top = minOf(by * BLOCK, height - BLOCK)
        for (bx in 0 until across) {
            val left = minOf(bx * BLOCK, width - BLOCK)
            var sum = 0
            var min = 255
            var max = 0
            for (y in top until top + BLOCK) {
                for (x in left until left + BLOCK) {
                    val v = data[y * width + x]
                    sum += v
                    if (v < min) min = v
                    if (v > max) max = v
                }
            }
            var level = sum.toDouble() / (BLOCK * BLOCK)
            if (max - min <= MIN_DYNAMIC_RANGE) {
                // A flat block: assume it is background unless its neighbours say otherwise.
                level = min / 2.0
                if (bx > 0 && by > 0) {
                    val around =
                        (levels[(by - 1) * across + bx] + 2 * levels[by * across + bx - 1] + levels[(by - 1) * across + bx - 1]) / 4
                    if (min < around) level = around
                }
            }
            levels[by * across + bx] = level
        }
    }
    for (by in 0 until down) {
        val top = minOf(by * BLOCK, height - BLOCK)
        val cy = minOf(maxOf(by, 2), down - 3)
        for (bx in 0 until across) {
            val left = minOf(bx * BLOCK, width - BLOCK)
            val cx = minOf(maxOf(bx, 2), across - 3)
            var sum = 0.0
            var n = 0
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val yy = cy + dy
                    val xx = cx + dx
                    if (yy < 0 || xx < 0 || yy >= down || xx >= across) continue
                    sum += levels[yy * across + xx]
                    n++
                }
            }
            val threshold = sum / n
            for (y in top until top + BLOCK) {
                for (x in left until left + BLOCK) out[y * width + x] = if (data[y * width + x] <= threshold) 1 else 0
            }
        }
    }
    return BitImage(out, width, height)
}

internal fun invert(image: BitImage): BitImage {
    val out = ByteArray(image.data.size)
    for (i in out.indices) out[i] = (image.data[i].toInt() xor 1).toByte()
    return BitImage(out, image.width, image.height)
}
