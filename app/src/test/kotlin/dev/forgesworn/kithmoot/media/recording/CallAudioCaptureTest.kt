package dev.forgesworn.kithmoot.media.recording

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.*

class CallAudioCaptureTest {
    private fun pcm(sample: Short): ByteBuffer = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> repeat(480) { buffer.putShort(sample) }; buffer.flip() }

    @Test fun `explicit local permission and pause produce a playable file with paused time omitted`() {
        val directory = Files.createTempDirectory("capture-").toFile()
        var time = 0L
        val capture = CallAudioCapture(File(directory, "call.wav")) { time }
        try {
            capture.setInputs(emptyMap(), local = true)
            capture.localSamples(pcm(1234), 16, 48_000, 1, 480)
            time = 10_000_000
            capture.pause()
            time += 1_000_000_000
            capture.localSamples(pcm(9999), 16, 48_000, 1, 480)
            capture.resume()
            capture.localSamples(pcm(-4321), 16, 48_000, 1, 480)
            time += 10_000_000
            val file = capture.finish()
            assertEquals(44L + 960 * 2, file.length())
            val data = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1234, data.getShort(44).toInt())
            assertEquals(-4321, data.getShort(44 + 480 * 2).toInt())
        } finally { capture.discard(); directory.deleteRecursively() }
    }

    @Test fun `capture errors never return a successful file and discard removes partial output`() {
        val directory = Files.createTempDirectory("capture-error-").toFile()
        val file = File(directory, "call.wav")
        val capture = CallAudioCapture(file)
        try {
            capture.setInputs(emptyMap(), local = true)
            capture.localSamples(pcm(1000), 16, 44_100, 1, 480)
            assertNotNull(capture.error())
            assertFails { capture.finish() }
            assertFalse(file.exists())
        } finally { capture.discard(); directory.deleteRecursively() }
    }

    @Test fun `ungranted local samples cannot enter an export`() {
        val directory = Files.createTempDirectory("capture-muted-").toFile()
        var time = 0L
        val capture = CallAudioCapture(File(directory, "call.wav")) { time }
        try {
            capture.localSamples(pcm(9999), 16, 48_000, 1, 480)
            time = 10_000_000
            val file = capture.finish()
            val data = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            for (offset in 44 until data.capacity() step 2) assertEquals(0, data.getShort(offset).toInt())
        } finally { capture.discard(); directory.deleteRecursively() }
    }

    @Test fun `detachment preserves accepted samples and omits asynchronous finalisation delay`() {
        val directory = Files.createTempDirectory("capture-detach-").toFile()
        var time = 0L
        val capture = CallAudioCapture(File(directory, "call.wav")) { time }
        try {
            capture.setInputs(emptyMap(), local = true)
            capture.localSamples(pcm(1234), 16, 48_000, 1, 480)
            time = 10_000_000
            capture.detachInputs()
            capture.detachInputs()
            time += 30_000_000_000
            capture.localSamples(pcm(9999), 16, 48_000, 1, 480)
            assertFails { capture.setInputs(emptyMap(), local = true) }
            assertFails { capture.pause() }
            assertFails { capture.resume() }
            val file = capture.finish()
            assertEquals(44L + 960, file.length())
            val data = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            for (offset in 44 until data.capacity() step 2) assertEquals(1234, data.getShort(offset).toInt())
        } finally { capture.discard(); directory.deleteRecursively() }
    }

    @Test fun `failed finalisation discards encoder resources on their owning worker`() {
        val directory = Files.createTempDirectory("capture-worker-").toFile()
        val file = File(directory, "call.mp4")
        val time = java.util.concurrent.atomic.AtomicLong()
        val wrote = java.util.concurrent.atomic.AtomicReference<Thread>()
        val closed = java.util.concurrent.atomic.AtomicReference<Thread>()
        val discarded = java.util.concurrent.atomic.AtomicReference<Thread>()
        val capture = CallAudioCapture(file, output = { destination ->
            check(destination.createNewFile())
            object : PcmFileOutput {
                override fun write(samples: ShortArray) { wrote.set(Thread.currentThread()) }
                override fun close() { closed.set(Thread.currentThread()); error("Encoder finalisation failed") }
                override fun discard() { discarded.set(Thread.currentThread()); destination.delete() }
            }
        }, clock = time::get)
        try {
            capture.setInputs(emptyMap(), local = true)
            capture.localSamples(pcm(1234), 16, 48_000, 1, 480)
            time.set(10_000_000)
            assertFails { capture.finish() }
            assertNotNull(wrote.get())
            assertSame(wrote.get(), closed.get())
            assertSame(wrote.get(), discarded.get())
            assertNotSame(Thread.currentThread(), discarded.get())
            assertFalse(file.exists())
        } finally { capture.discard(); directory.deleteRecursively() }
    }

    @Test fun `video timeline revokes input during pause failure and detachment`() {
        val directory = Files.createTempDirectory("capture-clock-").toFile()
        val time = java.util.concurrent.atomic.AtomicLong()
        lateinit var timeline: () -> Long?
        val pauseEvents = mutableListOf<Pair<Boolean, Long?>>()
        val file = File(directory, "call.mp4")
        val capture = CallAudioCapture(file, output = { destination ->
            destination.writeText("synthetic container")
            object : PcmFileOutput {
                override fun bindTimeline(clock: () -> Long?) { timeline = clock }
                override fun pauseInputs(paused: Boolean) { pauseEvents += paused to timeline() }
                override fun detachInputs() { assertNull(timeline()) }
                override fun write(samples: ShortArray) {}
                override fun close() {}
                override fun discard() { destination.delete() }
            }
        }, clock = time::get)
        try {
            assertEquals(0L, timeline())
            time.set(10_000_000)
            assertEquals(480L, timeline())
            capture.pause()
            assertNull(timeline())
            time.addAndGet(30_000_000_000)
            capture.resume()
            assertEquals(480L, timeline())
            assertEquals(listOf<Pair<Boolean, Long?>>(true to null, false to null), pauseEvents)
            time.addAndGet(10_000_000)
            assertEquals(960L, timeline())
            capture.setInputs(emptyMap(), local = true)
            capture.localSamples(pcm(1000), 16, 44_100, 1, 480)
            assertNull(timeline(), "A failed capture cannot admit more video")
            capture.detachInputs()
            assertNull(timeline())
        } finally { capture.discard(); directory.deleteRecursively() }
    }
}
