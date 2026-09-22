package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoLadderTest {
    @Test fun `a one-to-one call keeps the camera as captured`() {
        assertEquals(VideoLadder.FULL, VideoLadder.rungFor(0))
        assertEquals(VideoLadder.FULL, VideoLadder.rungFor(1))
        assertEquals(VideoRung(1280, 720, 30, 1_200_000), VideoLadder.FULL)
    }

    @Test fun `a small group steps down once and a larger one again`() {
        assertEquals(VideoLadder.MEDIUM, VideoLadder.rungFor(2))
        assertEquals(VideoLadder.MEDIUM, VideoLadder.rungFor(3))
        assertEquals(VideoLadder.SMALL, VideoLadder.rungFor(4))
        assertEquals(VideoLadder.SMALL, VideoLadder.rungFor(12))
    }

    @Test fun `every rung costs less than the one above it`() {
        val rungs = listOf(VideoLadder.FULL, VideoLadder.MEDIUM, VideoLadder.SMALL)
        for ((above, below) in rungs.zipWithNext()) {
            assertTrue(below.width * below.height < above.width * above.height)
            assertTrue(below.fps <= above.fps)
            assertTrue(below.maxBitrateBps < above.maxBitrateBps)
        }
    }

    @Test fun `only the camera is capped`() {
        assertTrue(isCameraTrackId("camera-1f2e3d"))
        assertFalse(isCameraTrackId("screen-1f2e3d"))
        assertFalse(isCameraTrackId("mic-1f2e3d"))
        assertFalse(isCameraTrackId("camera"))
    }
}
