package dev.forgesworn.kithmoot.media.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentDecodePolicyTest {
    @Test fun `dimensions frames and aggregate RGBA limits are exact`() {
        assertEquals(4, checkedImageGeometry(1, 1, 1).rgbaBytes)
        assertEquals(MAX_IMAGE_RGBA_BYTES, checkedImageGeometry(256, 256, 256).rgbaBytes)
        assertEquals(8192 * 4, checkedImageGeometry(8192, 1, 1).rgbaBytes)
        assertThrows(AttachmentLimitException::class.java) { checkedImageGeometry(8193, 1, 1) }
        assertThrows(AttachmentLimitException::class.java) { checkedImageGeometry(4001, 4000, 1) }
        assertThrows(AttachmentLimitException::class.java) { checkedImageGeometry(256, 256, 257) }
        assertThrows(AttachmentLimitException::class.java) { checkedImageGeometry(512, 512, 65) }
        assertThrows(AttachmentLimitException::class.java) { checkedImageGeometry(0, 1, 1) }
    }

    @Test fun `main process rejects forged output layouts before mapping pixels`() {
        validateDecodedLayout(2, 1, intArrayOf(0), intArrayOf(0), 8, "image/png")
        validateDecodedLayout(1, 1, intArrayOf(0, 4), intArrayOf(100, 20), 8, "image/gif")
        assertThrows(IllegalArgumentException::class.java) {
            validateDecodedLayout(1, 1, intArrayOf(1), intArrayOf(0), 4, "image/png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDecodedLayout(1, 1, intArrayOf(0), intArrayOf(1), 4, "image/png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDecodedLayout(1, 1, intArrayOf(0, 4), intArrayOf(100), 8, "image/gif")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateDecodedLayout(1, 1, intArrayOf(0), intArrayOf(0), 8, "image/png")
        }
    }

    @Test fun `GIF sub centisecond delays cannot spin the UI`() {
        assertEquals(100, gifDelayMillis(0))
        assertEquals(100, gifDelayMillis(1))
        assertEquals(20, gifDelayMillis(2))
        assertEquals(655_350, gifDelayMillis(65_535))
    }
}
