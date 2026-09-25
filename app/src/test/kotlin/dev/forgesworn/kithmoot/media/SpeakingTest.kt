package dev.forgesworn.kithmoot.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakingTest {

    @Test
    fun `thresholds match the web for true RMS`() {
        assertEquals(0.02, SpeakingLevels.RMS.on)
        assertEquals(0.01, SpeakingLevels.RMS.off)
        assertEquals(400L, SpeakingLevels.RMS.hangoverMs)
        // Receive-side levels are peak-based, so the same shape sits higher.
        assertTrue(SpeakingLevels.STATS.on > SpeakingLevels.RMS.on)
        assertEquals(SpeakingLevels.STATS.on / 2, SpeakingLevels.STATS.off)
    }

    @Test
    fun `starts at once when the level clears on`() {
        val detector = SpeakingDetector()
        assertFalse(detector.update(0.019, 0))
        assertTrue(detector.update(0.02, 10))
    }

    @Test
    fun `a level between off and on neither starts nor stops`() {
        val detector = SpeakingDetector()
        assertFalse(detector.update(0.015, 0))
        detector.update(0.05, 100)
        assertTrue(detector.update(0.015, 200))
        assertTrue(detector.update(0.015, 5_000))
    }

    @Test
    fun `the hangover carries the gaps between words`() {
        val detector = SpeakingDetector()
        detector.update(0.05, 0)
        assertTrue(detector.update(0.0, 100))
        assertTrue(detector.update(0.0, 499))
        // Back above off inside the gap: the hangover starts again.
        assertTrue(detector.update(0.012, 450))
        assertTrue(detector.update(0.0, 500))
        assertTrue(detector.update(0.0, 899))
        assertFalse(detector.update(0.0, 900))
    }

    @Test
    fun `reset forgets a source that went away`() {
        val detector = SpeakingDetector()
        detector.update(0.5, 0)
        detector.reset()
        assertFalse(detector.speaking)
    }

    @Test
    fun `a set keeps one detector per key and drops the departed`() {
        val set = SpeakingSet(SpeakingLevels.STATS)
        set.update("a", 0.06, 0)
        set.update("b", 0.04, 0)
        assertEquals(setOf("a"), set.active())
        set.retain(setOf("b"))
        assertFalse(set.speaking("a"))
        assertEquals(emptySet(), set.active())
    }

    @Test
    fun `energy deltas give the level over the interval`() {
        // A steady 0.1 over 0.2 s adds 0.1^2 * 0.2 of energy.
        val level = levelFromEnergy(1.0, 10.0, 1.0 + 0.01 * 0.2, 10.2)
        assertTrue(abs(level!! - 0.1) < 1e-9)
        assertNull(levelFromEnergy(1.0, 10.0, 1.0, 10.0), "no audio in between")
        assertNull(levelFromEnergy(1.0, 10.0, 0.5, 10.2), "counters went backwards")
    }

    @Test
    fun `the meter measures true RMS of 16-bit PCM and drains`() {
        val meter = LevelMeter()
        assertNull(meter.drain())
        // A full-scale-halved sine has RMS 0.5 / sqrt(2).
        val frames = 480
        val bytes = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val value = (0.5 * 32767 * sin(2 * PI * 10 * i / frames)).toInt()
            bytes[2 * i] = (value and 0xff).toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        meter.addPcm16(bytes)
        val rms = meter.drain()!!
        assertTrue(abs(rms - 0.5 / kotlin.math.sqrt(2.0)) < 0.001, "rms was $rms")
        assertNull(meter.drain(), "a drain starts the next interval")
    }

    @Test
    fun `a quiet room stays dark and speech lights on the web scale`() {
        val detector = SpeakingDetector(SpeakingLevels.RMS)
        val meter = LevelMeter()
        fun pcm(amplitude: Double): ByteArray {
            val bytes = ByteArray(960)
            for (i in 0 until 480) {
                val value = (amplitude * 32767 * sin(2 * PI * 7 * i / 480)).toInt()
                bytes[2 * i] = (value and 0xff).toByte()
                bytes[2 * i + 1] = (value shr 8).toByte()
            }
            return bytes
        }
        meter.addPcm16(pcm(0.005))
        assertFalse(detector.update(meter.drain()!!, 0))
        meter.addPcm16(pcm(0.1))
        assertTrue(detector.update(meter.drain()!!, 200))
    }
}
