package dev.forgesworn.kithmoot.media.recording

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.*

class PcmRecordingTest {
    private fun pcm(vararg samples: Int): ByteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> samples.forEach { buffer.putShort(it.toShort()) }; buffer.flip() }

    @Test fun `mixes both sides once converts stereo and saturates`() {
        val mixer = PcmRecordingMixer()
        mixer.setSources(setOf("local", "remote"))
        mixer.offer("local", pcm(20_000, -20_000), 16, 48_000, 1, 2, 0)
        mixer.offer("remote", pcm(30_000, 10_000, -30_000, -10_000), 16, 48_000, 2, 2, 0)
        assertContentEquals(shortArrayOf(32767, -32768, 0), mixer.drain(3))
    }

    @Test fun `revoked sources and paused samples never escape into the export`() {
        val mixer = PcmRecordingMixer()
        mixer.setSources(setOf("allowed", "removed"))
        mixer.offer("removed", pcm(1000), 16, 48_000, 1, 1, 0)
        mixer.setSources(setOf("allowed"))
        assertContentEquals(shortArrayOf(0), mixer.drain(1))
        mixer.pause(true)
        mixer.offer("allowed", pcm(2000), 16, 48_000, 1, 1, 1)
        mixer.pause(false)
        assertContentEquals(shortArrayOf(0), mixer.drain(1))
        mixer.offer("removed", pcm(3000), 16, 48_000, 1, 1, 2)
        assertContentEquals(shortArrayOf(0), mixer.drain(1))
    }

    @Test fun `source count pending audio and formats are explicitly bounded`() {
        val mixer = PcmRecordingMixer(1)
        assertFails { mixer.setSources(setOf("one", "two")) }
        mixer.setSources(setOf("one"))
        assertFails { mixer.offer("one", pcm(1), 16, 44_100, 1, 1, 0) }
        assertFails { mixer.offer("one", pcm(1), 16, 48_000, 1, 1, PcmRecordingMixer.CAPACITY.toLong()) }
    }

    @Test fun `callback scheduling jitter does not accumulate gaps in continuous PCM`() {
        val mixer = PcmRecordingMixer()
        mixer.setSources(setOf("remote"))
        val buffer = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)
        repeat(480) { buffer.putShort(1000) }; buffer.flip()
        repeat(100) { index ->
            // A delayed first delivery in each burst, followed by catch-up.
            val arrival = index * 480L + if (index % 10 == 1) 1000 else 0
            mixer.offer("remote", buffer, 16, 48_000, 1, 480, arrival)
        }
        assertTrue(mixer.drain(48_000).all { it == 1000.toShort() })
    }

    @Test fun `writes a playable mono wave header and enforces the file cap`() {
        val directory = Files.createTempDirectory("recording-wave-").toFile()
        try {
            val file = File(directory, "call.wav")
            PcmWaveFile(file, 48).use { writer ->
                writer.write(shortArrayOf(123, -456))
                assertFails { writer.write(shortArrayOf(789)) }
            }
            val bytes = file.readBytes()
            assertEquals(48, bytes.size)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(40, header.getInt(4)); assertEquals(48_000, header.getInt(24)); assertEquals(4, header.getInt(40))
            assertEquals(123, header.getShort(44).toInt()); assertEquals(-456, header.getShort(46).toInt())
            assertFails { PcmWaveFile(file) }
            assertContentEquals(bytes, file.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
