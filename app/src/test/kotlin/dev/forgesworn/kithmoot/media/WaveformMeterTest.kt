package dev.forgesworn.kithmoot.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveformMeterTest {
    @Test fun `PCM peaks retain transients and draining clears the old trace without touching the buffer`() {
        val data = ByteBuffer.allocateDirect(2048).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(6, Short.MAX_VALUE); data.putShort(8, Short.MIN_VALUE)
        data.position(16)
        val meter = WaveformMeter()
        meter.addPcm16(data, 2048)
        assertEquals(16, data.position())
        val reading = meter.drain()
        assertTrue(reading.level!! > 0)
        assertEquals(256, reading.waveform.size)
        assertEquals(listOf(-1f, 32767f / 32768f), reading.waveform.take(2))
        assertTrue(reading.waveform.drop(2).all { it == 0f })
        assertNull(meter.drain().level)
        assertTrue(meter.drain().waveform.isEmpty())
        meter.addPcm16(ByteBuffer.allocate(4), 4)
        assertEquals(listOf(0f, 0f, 0f, 0f), meter.drain().waveform)
    }
    @Test fun `empty callback has no trace`() {
        val meter = WaveformMeter()
        meter.addPcm16(ByteBuffer.allocate(0), 0)
        assertNull(meter.drain().level)
        assertTrue(meter.drain().waveform.isEmpty())
    }
}
