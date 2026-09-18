package dev.forgesworn.kithmoot.media.effects

/**
 * Carrying the segmentation mask from one frame to the next, so the edge of a
 * person holds still.
 *
 * A port of `MaskSmoother` in the web client's `video-effects.ts`, constant for
 * constant. The problem it solves is the same on both: the segmenter is run
 * from scratch on every frame and nothing carries over, so a pixel on the
 * boundary of a perfectly still shoulder flips between 0.45 and 0.55 confidence
 * frame after frame. Blur hid that. Replacement does not - and blur is not on
 * offer here - so the edge crawls unless something remembers the last frame.
 *
 * Two ideas share one buffer:
 *
 * 1. **An exponential moving average with a motion-aware weight.** A plain EMA
 *    either buzzes (weight too high) or trails a ghost behind a moving arm
 *    (weight too low), and choosing between those is choosing which artefact to
 *    ship. Making the weight depend on how much *this* pixel changed dodges it:
 *    a still pixel is averaged hard, a pixel the person has just moved into is
 *    taken as it comes.
 *
 * 2. **A small erosion**, off by default. The model over-claims at the hair
 *    line. On the web the same pull-in is done on the stencil canvas, where the
 *    GPU pays for it; here [FrameCompositor] does the same trick with four
 *    offset draws, so this stays off unless somebody has a reason.
 *
 * Buffers are allocated once per mask size and reused: this runs on every pixel
 * of every frame.
 */
class MaskSmoother(
    /** Weight on the new frame where it agrees with the last one. */
    calmAlpha: Float = MASK_ALPHA_CALM,
    /** Weight on the new frame where it disagrees with it completely. */
    movingAlpha: Float = MASK_ALPHA_MOVING,
    /** Confidence change at which [movingAlpha] is reached. */
    motionDelta: Float = MASK_MOTION_DELTA,
    /** Mask pixels to pull the person's edge in by. 0 leaves it alone. */
    erodeRadius: Int = MASK_ERODE_PX,
) {
    private val calm = calmAlpha.coerceIn(0f, 1f)
    private val moving = movingAlpha.coerceIn(0f, 1f)
    private val delta = maxOf(1e-3f, motionDelta)
    private val radius = maxOf(0, erodeRadius)

    private var width = 0
    private var height = 0
    private var state: FloatArray? = null
    private var scratch: FloatArray? = null
    private var eroded: FloatArray? = null

    /**
     * Forget everything.
     *
     * Called wherever the source changes - a camera flip, a restarted capturer,
     * a background switched on mid-call. A mask averaged across a camera swap
     * describes a person who is in neither picture.
     */
    fun reset() {
        state = null
    }

    /**
     * The smoothed mask for this frame.
     *
     * The returned array is reused between calls and must not be held on to.
     * `data` is read for `width * height` entries and is never written.
     */
    fun push(data: FloatArray, width: Int, height: Int): FloatArray {
        val pixels = width * height
        require(pixels > 0) { "a mask needs a size" }
        require(data.size >= pixels) { "mask data of ${data.size} is short of $pixels" }

        val previous = state
        if (previous == null || this.width != width || this.height != height) {
            this.width = width
            this.height = height
            val fresh = FloatArray(pixels)
            // The first frame of a new source has nothing to average against,
            // and inventing a history for it would mean fading the person in.
            System.arraycopy(data, 0, fresh, 0, pixels)
            state = fresh
            scratch = FloatArray(pixels)
            eroded = FloatArray(pixels)
        } else {
            val span = moving - calm
            val inverseDelta = 1f / delta
            for (i in 0 until pixels) {
                val was = previous[i]
                val now = data[i]
                val step = now - was
                val change = if (step < 0f) -step else step
                val t = change * inverseDelta
                previous[i] = was + step * (if (t >= 1f) moving else calm + span * t)
            }
        }

        val current = state!!
        if (radius == 0) return current
        erode(current, scratch!!, eroded!!, width, height, radius)
        return eroded!!
    }

    companion object {
        /**
         * How much of a new frame's confidence to believe where it agrees with
         * the last frame. Damping the agreeing case to about a third means a
         * still edge settles over three or four frames instead of buzzing.
         */
        const val MASK_ALPHA_CALM = 0.34f

        /**
         * ...and how much to believe it where it disagrees. A pixel that went
         * from "definitely room" to "definitely person" is movement, not noise,
         * and movement must not be smeared, so it is followed outright.
         */
        const val MASK_ALPHA_MOVING = 1f

        /** The confidence change at which a pixel counts as having moved rather
         *  than wobbled. Below it the weight ramps between the two above. */
        const val MASK_MOTION_DELTA = 0.4f

        /** Erosion radius in mask pixels. Zero: [FrameCompositor] pulls the
         *  outline in with offset draws instead, which the canvas pays for. */
        const val MASK_ERODE_PX = 0
    }
}

/**
 * Separable minimum filter: the person's edge, pulled in by `radius`.
 *
 * Separable because the minimum over a square window is the minimum of the row
 * minima, so an `r`-radius erosion costs `2 * (2r + 1)` comparisons a pixel
 * rather than `(2r + 1)^2`.
 *
 * Out-of-bounds neighbours are skipped rather than treated as background, which
 * would eat a pixel off every edge of the frame and cut a person standing at
 * the side of it in half.
 *
 * Both passes walk rows, never columns. The obvious way to write the vertical
 * one - for each pixel, look above and below - strides the array by a whole row
 * per step and misses the cache on almost every read; measured on the web that
 * cost more than the rest of the effect put together.
 */
fun erode(
    src: FloatArray,
    scratch: FloatArray,
    out: FloatArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val from = if (x - radius < 0) 0 else x - radius
            val to = if (x + radius >= width) width - 1 else x + radius
            var min = src[row + from]
            for (i in from + 1..to) {
                val v = src[row + i]
                if (v < min) min = v
            }
            scratch[row + x] = min
        }
    }

    for (y in 0 until height) {
        val row = y * width
        val from = if (y - radius < 0) 0 else y - radius
        val to = if (y + radius >= height) height - 1 else y + radius
        System.arraycopy(scratch, from * width, out, row, width)
        for (i in from + 1..to) {
            val other = i * width
            for (x in 0 until width) {
                val a = out[row + x]
                val b = scratch[other + x]
                if (b < a) out[row + x] = b
            }
        }
    }
}

/**
 * Where the cut between person and room falls, and how wide the soft band
 * either side of it is.
 *
 * Both are the web client's numbers. The threshold is nudged past the middle so
 * the pixels the model is merely half sure about go with the room rather than
 * with the person - at the hair line and along a shoulder those are a fringe of
 * the real room, and against a replacement they get drawn on the beach. The
 * feather is narrow, 0.26 rather than the 0.4 blur could afford, because a wide
 * ramp against a replacement reads as a halo: the pixels being faded in are the
 * real room and they no longer match what is behind them.
 */
const val MASK_THRESHOLD = 0.55f
const val MASK_FEATHER = 0.26f

/** Hermite smoothstep, so the mask edge ramps rather than steps. */
fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Turn a confidence mask into the alpha byte per pixel: 0 is the room, 255 is
 * the person.
 *
 * `out` is filled with `width * height` bytes and is reused between frames.
 */
fun maskToAlpha(
    mask: FloatArray,
    out: ByteArray,
    width: Int,
    height: Int,
    threshold: Float = MASK_THRESHOLD,
    feather: Float = MASK_FEATHER,
) {
    val pixels = width * height
    require(out.size >= pixels) { "alpha buffer of ${out.size} is short of $pixels" }
    val low = threshold - feather / 2f
    val high = threshold + feather / 2f
    for (i in 0 until pixels) {
        val alpha = smoothstep(low, high, mask[i])
        out[i] = Math.round(alpha * 255f).toByte()
    }
}
