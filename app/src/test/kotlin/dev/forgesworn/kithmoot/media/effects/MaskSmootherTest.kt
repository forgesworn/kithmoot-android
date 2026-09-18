package dev.forgesworn.kithmoot.media.effects

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The part that stops the edge of a person crawling.
 *
 * The whole point of the motion-aware weight is that a still pixel and a moving
 * one are treated differently, which is a thing that can be asserted rather
 * than watched.
 */
class MaskSmootherTest {

    private fun frame(value: Float, pixels: Int = 4) = FloatArray(pixels) { value }

    @Test
    fun `the first frame of a source is taken as it comes`() {
        val smoother = MaskSmoother()
        val out = smoother.push(frame(0.8f), 2, 2)
        // Inventing a history for the first frame would fade the person in.
        for (v in out) assertEquals(0.8f, v, 1e-6f)
    }

    @Test
    fun `a small wobble is damped towards the last frame`() {
        val smoother = MaskSmoother()
        smoother.push(frame(0.50f), 2, 2)
        val out = smoother.push(frame(0.55f), 2, 2)
        // 0.05 of change against a motion delta of 0.4 is well inside the calm
        // band, so only about a third of the step is taken.
        val expected = 0.50f + 0.05f * (0.34f + (1f - 0.34f) * (0.05f / 0.4f))
        assertEquals(expected, out[0], 1e-5f)
        assertTrue(out[0] < 0.55f, "a wobble must not be followed outright")
        assertTrue(out[0] > 0.50f, "a wobble must not be ignored either")
    }

    @Test
    fun `a pixel the person has moved into is followed outright`() {
        val smoother = MaskSmoother()
        smoother.push(frame(0.0f), 2, 2)
        val out = smoother.push(frame(1.0f), 2, 2)
        assertEquals(1.0f, out[0], 1e-6f)
    }

    @Test
    fun `a still edge settles within a handful of frames`() {
        val smoother = MaskSmoother()
        smoother.push(frame(0.40f), 2, 2)
        var last = 0f
        repeat(6) { last = smoother.push(frame(0.60f), 2, 2)[0] }
        assertTrue(abs(0.60f - last) < 0.02f, "settled to $last after six frames")
    }

    @Test
    fun `a buzzing pixel does not buzz in the output`() {
        val smoother = MaskSmoother()
        smoother.push(frame(0.50f), 2, 2)
        var swing = 0f
        var previous = 0.5f
        repeat(20) { i ->
            val noisy = if (i % 2 == 0) 0.45f else 0.55f
            val out = smoother.push(frame(noisy), 2, 2)[0]
            if (i > 4) swing = maxOf(swing, abs(out - previous))
            previous = out
        }
        // Raw, the pixel swings 0.1 every frame. Smoothed it must swing much less.
        assertTrue(swing < 0.05f, "the smoothed pixel still swings by $swing")
    }

    @Test
    fun `a reset forgets the history rather than averaging across a camera swap`() {
        val smoother = MaskSmoother()
        smoother.push(frame(0.0f), 2, 2)
        smoother.reset()
        val out = smoother.push(frame(1.0f), 2, 2)
        assertEquals(1.0f, out[0], 1e-6f)
    }

    @Test
    fun `a change of mask size starts again`() {
        val smoother = MaskSmoother()
        smoother.push(frame(0.0f, 4), 2, 2)
        val out = smoother.push(frame(1.0f, 9), 3, 3)
        assertEquals(9, out.size)
        assertEquals(1.0f, out[0], 1e-6f)
    }

    @Test
    fun `calm and moving weights are the web client's`() {
        assertEquals(0.34f, MaskSmoother.MASK_ALPHA_CALM)
        assertEquals(1f, MaskSmoother.MASK_ALPHA_MOVING)
        assertEquals(0.4f, MaskSmoother.MASK_MOTION_DELTA)
    }

    // --- erosion -------------------------------------------------------------

    @Test
    fun `erosion pulls an edge in by the radius`() {
        // A 5x5 block of person with a one-pixel border of room.
        val w = 7
        val h = 7
        val src = FloatArray(w * h)
        for (y in 1..5) for (x in 1..5) src[y * w + x] = 1f
        val out = FloatArray(w * h)
        erode(src, FloatArray(w * h), out, w, h, 1)
        for (y in 0 until h) for (x in 0 until w) {
            val inside = y in 2..4 && x in 2..4
            assertEquals(if (inside) 1f else 0f, out[y * w + x], 1e-6f, "at $x,$y")
        }
    }

    @Test
    fun `a person standing at the edge of the frame is not cut in half`() {
        // Out-of-bounds neighbours are skipped, not treated as room.
        val w = 4
        val h = 4
        val src = FloatArray(w * h) { 1f }
        val out = FloatArray(w * h)
        erode(src, FloatArray(w * h), out, w, h, 1)
        for (v in out) assertEquals(1f, v, 1e-6f)
    }

    @Test
    fun `an eroding smoother returns the eroded buffer`() {
        val smoother = MaskSmoother(erodeRadius = 1)
        val w = 5
        val h = 5
        val data = FloatArray(w * h)
        for (y in 1..3) for (x in 1..3) data[y * w + x] = 1f
        val out = smoother.push(data, w, h)
        assertEquals(1f, out[2 * w + 2], 1e-6f)
        assertEquals(0f, out[1 * w + 1], 1e-6f, "the outline should have been pulled in")
    }

    // --- the cut -------------------------------------------------------------

    @Test
    fun `confidence below the soft band is all room and above it is all person`() {
        val out = ByteArray(3)
        maskToAlpha(floatArrayOf(0f, MASK_THRESHOLD, 1f), out, 3, 1)
        assertEquals(0, out[0].toInt() and 0xFF)
        assertEquals(128, out[1].toInt() and 0xFF, "the threshold is the middle of the ramp")
        assertEquals(255, out[2].toInt() and 0xFF)
    }

    @Test
    fun `the cut sits past the middle, so doubtful pixels go with the room`() {
        val out = ByteArray(1)
        maskToAlpha(floatArrayOf(0.5f), out, 1, 1)
        assertTrue((out[0].toInt() and 0xFF) < 128, "a half-sure pixel must lean towards the room")
    }

    @Test
    fun `the soft band is narrow enough not to halo`() {
        assertEquals(0.26f, MASK_FEATHER)
        val out = ByteArray(2)
        // Just outside the band either way: fully one thing or fully the other.
        maskToAlpha(floatArrayOf(MASK_THRESHOLD - MASK_FEATHER, MASK_THRESHOLD + MASK_FEATHER), out, 2, 1)
        assertEquals(0, out[0].toInt() and 0xFF)
        assertEquals(255, out[1].toInt() and 0xFF)
    }

    @Test
    fun `smoothstep ramps rather than steps`() {
        assertEquals(0f, smoothstep(0f, 1f, -1f))
        assertEquals(0.5f, smoothstep(0f, 1f, 0.5f), 1e-6f)
        assertEquals(1f, smoothstep(0f, 1f, 2f))
        assertEquals(1f, smoothstep(1f, 1f, 1f), 1e-6f)
    }
}
