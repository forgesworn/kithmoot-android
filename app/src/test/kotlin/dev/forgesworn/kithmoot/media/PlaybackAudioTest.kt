package dev.forgesworn.kithmoot.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class PlaybackAudioTest {
    @Test fun `screen-only or muted capture never forwards microphone samples`() {
        val audio = PlaybackAudio()
        val buffer = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 480) buffer.putShort(i * 2, 2345)
        audio.fill(buffer, 2, 1, 48_000, 960)
        assertTrue((0 until 960).all { buffer.get(it) == 0.toByte() })
        audio.microphone = true
        buffer.putShort(0, 2345)
        audio.fill(buffer, 2, 1, 48_000, 960)
        assertEquals(2345.toShort(), buffer.getShort(0))
        audio.microphone = false
        audio.fill(buffer, 99, 2, 44_100, 960)
        assertTrue((0 until 960).all { buffer.get(it) == 0.toByte() })
    }
    @Test fun `mixing clips instead of wrapping into inverted audio`() {
        assertEquals(Short.MAX_VALUE, mixPcm(30_000, 10_000))
        assertEquals(Short.MIN_VALUE, mixPcm(-30_000, -10_000))
        assertEquals(1_234.toShort(), mixPcm(0, 1_234))
    }
}
