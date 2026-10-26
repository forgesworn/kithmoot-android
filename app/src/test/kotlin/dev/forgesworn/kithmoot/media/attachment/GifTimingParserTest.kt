package dev.forgesworn.kithmoot.media.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifTimingParserTest {
    @Test fun `keeps nonzero raw Movie timing separate from normalised UI delays`() {
        val bytes = gif(listOf(1, 2, 10, 20, 65_535))
        val timing = GifTimingParser.parse(bytes)
        assertEquals(listOf(10, 20, 100, 200, 655_350), timing.seekDelaysMs)
        assertEquals(listOf(100, 20, 100, 200, 655_350), timing.delaysMs)
        assertThrows(IllegalArgumentException::class.java) { GifTimingParser.parse(bytes + 1.toByte()) }
    }

    @Test fun `rejects explicit zero and missing graphics control delays`() {
        for (bytes in listOf(gif(listOf(20, 0, 20)), gif(listOf(0, 0)), gif(listOf(20, null, 20)))) {
            assertThrows(IllegalArgumentException::class.java) { GifTimingParser.parse(bytes) }
        }
    }

    @Test fun `walks extension sub blocks and rejects partial images`() {
        val prefix = gif(listOf(7))
        val withComment = prefix.copyOfRange(0, prefix.size - 1) +
            byteArrayOf(0x21, 0xfe.toByte(), 3, 1, 2, 3, 0, 0x3b)
        assertEquals(listOf(70), GifTimingParser.parse(withComment).delaysMs)
        assertThrows(IllegalArgumentException::class.java) { GifTimingParser.parse(prefix.copyOf(prefix.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { GifTimingParser.parse(byteArrayOf()) }
    }

    @Test fun `rejects more than 256 frames and oversized aggregate output`() {
        assertThrows(AttachmentLimitException::class.java) { GifTimingParser.parse(gif(List(257) { 10 })) }
        assertThrows(AttachmentLimitException::class.java) { GifTimingParser.parse(gif(List(65) { 10 }, 512, 512)) }
    }

    private fun gif(delays: List<Int?>, width: Int = 1, height: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(width and 0xff); out.write(width ushr 8)
        out.write(height and 0xff); out.write(height ushr 8)
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        delays.forEach { delay ->
            if (delay != null) out.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 0, (delay and 0xff).toByte(), (delay ushr 8).toByte(), 0, 0))
            out.write(byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4c, 1, 0))
        }
        out.write(0x3b)
        return out.toByteArray()
    }
}
