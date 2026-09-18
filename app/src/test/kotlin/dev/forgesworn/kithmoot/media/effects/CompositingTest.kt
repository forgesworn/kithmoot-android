package dev.forgesworn.kithmoot.media.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic that decides where the sea goes.
 *
 * These are the failures that would otherwise only be visible on a handset:
 * a photograph stretched to the wrong shape, a working buffer with an odd
 * number of pixels, a scene that ends up on its side because the rotation was
 * passed on rather than applied.
 */
class CompositingTest {

    // --- cover-fitting -------------------------------------------------------

    @Test
    fun `a matching aspect ratio fills exactly`() {
        val rect = coverRect(1280, 720, 640, 360)
        assertEquals(0f, rect.dx)
        assertEquals(0f, rect.dy)
        assertEquals(640f, rect.dw)
        assertEquals(360f, rect.dh)
    }

    @Test
    fun `a landscape photograph in a portrait frame overhangs sideways and is centred`() {
        // 1280x720 into 360x640: it has to be 1138 wide to be 640 tall, so 389
        // pixels are cropped off each side.
        val rect = coverRect(1280, 720, 360, 640)
        assertEquals(640f, rect.dh, "the short side is what has to be covered")
        assertEquals(1137.7778f, rect.dw, 0.01f)
        assertEquals(-(rect.dw - 360f) / 2f, rect.dx, 0.01f)
        assertEquals(0f, rect.dy)
        // Centred means the same amount off each end.
        assertEquals(360f - rect.dx, rect.right, 0.01f)
    }

    @Test
    fun `cover never leaves a gap`() {
        for (src in listOf(1280 to 720, 720 to 1280, 512 to 512, 1920 to 800)) {
            for (dst in listOf(640 to 360, 360 to 640, 480 to 480, 320 to 568)) {
                val rect = coverRect(src.first, src.second, dst.first, dst.second)
                assertTrue(rect.dx <= 0.01f, "left edge uncovered for $src into $dst")
                assertTrue(rect.dy <= 0.01f, "top edge uncovered for $src into $dst")
                assertTrue(rect.right >= dst.first - 0.01f, "right edge uncovered for $src into $dst")
                assertTrue(rect.bottom >= dst.second - 0.01f, "bottom edge uncovered for $src into $dst")
            }
        }
    }

    @Test
    fun `cover keeps the photograph's own shape`() {
        val rect = coverRect(1600, 900, 360, 640)
        assertEquals(1600f / 900f, rect.dw / rect.dh, 0.0001f)
    }

    @Test
    fun `a picture with no size at all fills the frame rather than dividing by zero`() {
        val rect = coverRect(0, 0, 640, 360)
        assertEquals(CoverRect(0f, 0f, 640f, 360f), rect)
    }

    // --- working size --------------------------------------------------------

    @Test
    fun `a landscape buffer is capped at the working width`() {
        val size = workingSize(1280, 720, rotation = 0)
        assertEquals(640, size.outWidth)
        assertEquals(360, size.outHeight)
        assertEquals(640, size.scaleWidth)
        assertEquals(360, size.scaleHeight)
    }

    @Test
    fun `a quarter turn swaps the buffer and the upright picture`() {
        // A phone held upright: the camera hands over 1280x720 with rotation 90.
        // Upright that is 720x1280, the cap falls on the 1280, and the buffer
        // we ask the capturer for is the other way round from what we draw.
        val size = workingSize(1280, 720, rotation = 90)
        assertEquals(360, size.outWidth)
        assertEquals(640, size.outHeight)
        assertEquals(640, size.scaleWidth)
        assertEquals(360, size.scaleHeight)
    }

    @Test
    fun `a portrait frame costs no more than a landscape one`() {
        val landscape = workingSize(1280, 720, rotation = 0)
        val portrait = workingSize(1280, 720, rotation = 90)
        assertEquals(landscape.outWidth * landscape.outHeight, portrait.outWidth * portrait.outHeight)
    }

    @Test
    fun `nothing is ever wider than the cap`() {
        for ((w, h) in listOf(1280 to 720, 720 to 1280, 1920 to 1080, 480 to 640)) {
            for (r in listOf(0, 90, 180, 270)) {
                val size = workingSize(w, h, r)
                assertTrue(size.outWidth <= MAX_WORKING_WIDTH, "${size.outWidth} from ${w}x$h at $r")
                assertTrue(size.outHeight <= MAX_WORKING_WIDTH, "${size.outHeight} from ${w}x$h at $r")
            }
        }
    }

    @Test
    fun `270 degrees is the same shape as 90`() {
        assertEquals(workingSize(1280, 720, 90), workingSize(1280, 720, 270))
    }

    @Test
    fun `180 degrees is the same shape as upright`() {
        assertEquals(workingSize(1280, 720, 0), workingSize(1280, 720, 180))
    }

    @Test
    fun `negative and over-turned rotations are normalised`() {
        assertEquals(workingSize(1280, 720, 90), workingSize(1280, 720, -270))
        assertEquals(workingSize(1280, 720, 90), workingSize(1280, 720, 450))
    }

    @Test
    fun `every dimension is even, whatever the camera hands over`() {
        val awkward = listOf(
            Triple(641, 481, 0),
            Triple(1001, 667, 90),
            Triple(355, 199, 270),
            Triple(1920, 1080, 0),
        )
        for ((w, h, r) in awkward) {
            val size = workingSize(w, h, r)
            for (value in listOf(size.scaleWidth, size.scaleHeight, size.outWidth, size.outHeight)) {
                assertEquals(0, value % 2, "odd dimension $value from ${w}x$h at $r")
                assertTrue(value >= 2)
            }
        }
    }

    @Test
    fun `a small camera is not blown up to the cap`() {
        val size = workingSize(320, 240, rotation = 0)
        assertEquals(320, size.outWidth)
        assertEquals(240, size.outHeight)
    }

    @Test
    fun `the aspect ratio survives the cap`() {
        val size = workingSize(1920, 1080, rotation = 0)
        assertEquals(1920f / 1080f, size.outWidth.toFloat() / size.outHeight, 0.01f)
    }

    @Test
    fun `a buffer with no size does not produce a zero-sized bitmap`() {
        val size = workingSize(0, 0, rotation = 0)
        assertTrue(size.outWidth >= 2 && size.outHeight >= 2)
    }

    // --- the mirror ----------------------------------------------------------

    @Test
    fun `the background is mirrored on the front camera and not on the back`() {
        assertTrue(mirrorBackground(frontFacing = true))
        assertFalse(mirrorBackground(frontFacing = false))
    }
}
