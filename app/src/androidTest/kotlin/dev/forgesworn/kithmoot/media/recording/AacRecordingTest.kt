package dev.forgesworn.kithmoot.media.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

class AacRecordingTest {
    @Test fun compressedExportDecodesBothTonesAndKeepsSampleDuration() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "synthetic-aac-${System.nanoTime()}").also { check(it.mkdirs()) }
        val file = File(directory, "call.m4a")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            val clock = AtomicLong()
            val capture = CallAudioCapture(file, output = { AacRecordingFile(it) }, clock = clock::get)
            try {
                capture.setInputs(emptyMap(), local = true)
                repeat(200) { block ->
                    if (block == 100) {
                        capture.pause()
                        clock.addAndGet(30_000_000_000)
                        capture.resume()
                    }
                    val buffer = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(480) { offset ->
                        val frame = block * 480 + offset
                        // A short final marker cannot pass merely because
                        // an earlier part of a long tone survives clipping.
                        // The initial chirp also gives a unique alignment
                        // marker for retained synthetic decoder samples.
                        val value = if (frame >= 95_040) 4000 * sin(frame * 2 * PI * 1100 / 48_000)
                            else if (frame < 4800) {
                                val seconds = frame / 48_000.0
                                4000 * sin(2 * PI * (220 * seconds + 7700 * seconds * seconds))
                            }
                            else 4000 * sin(frame * 2 * PI * 440 / 48_000) +
                                4000 * sin(frame * 2 * PI * 660 / 48_000)
                        buffer.putShort(value.toInt().toShort())
                    }
                    buffer.flip()
                    capture.localSamples(buffer, 16, 48_000, 1, 480)
                    clock.addAndGet(10_000_000)
                }
                assertEquals(file, capture.finish())
            } catch (error: Throwable) { capture.discard(); throw error }
            assertTrue("Compressed export is smaller than PCM", file.length() in 1 until 48_000 * 4L)
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            extractor.selectTrack(0)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder = codec
            codec.configure(format, null, null, 0); codec.start()
            val samples = ArrayList<Short>()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var packets = 0
            var lastPacketTime = 0L
            var firstDecodedTime: Long? = null
            var lastDecodedEnd = 0L
            val deadline = System.nanoTime() + 10_000_000_000
            while (!outputEnded && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            packets++
                            lastPacketTime = extractor.sampleTime
                            codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    try {
                        val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        if (info.size > 0) {
                            if (firstDecodedTime == null) firstDecodedTime = info.presentationTimeUs
                            lastDecodedEnd = info.presentationTimeUs + (info.size / 2L) * 1_000_000 / 48_000
                        }
                        while (buffer.remaining() >= 2) samples.add(buffer.short)
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            assertTrue("Complete export decodes", outputEnded)
            android.util.Log.i("KithMootRecordingTest", "AAC decoded=${samples.size}, packets=$packets, lastPacketUs=$lastPacketTime, inputFormat=$format, outputFormat=${codec.outputFormat}, bytes=${file.length()}")
            // Explicit diagnostic opt-in retains only this generated fixture,
            // never microphone audio, account material or room capabilities.
            if (InstrumentationRegistry.getArguments().getString("retainSyntheticCodecSample") == "true") {
                val retained = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir),
                    "synthetic-codec-probe").also { check(it.mkdirs() || it.isDirectory) }
                file.copyTo(File(retained, "synthetic.m4a"), overwrite = true)
                val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach(pcm::putShort)
                File(retained, "synthetic-decoded.pcm").writeBytes(pcm.array())
            }
            val codedFrames = (format.getLong(MediaFormat.KEY_DURATION) * 48_000 + 500_000) / 1_000_000
            val delay = format.getInteger(MediaFormat.KEY_ENCODER_DELAY)
            val padding = format.getInteger(MediaFormat.KEY_ENCODER_PADDING)
            assertEquals("Original PCM playback interval in platform gapless metadata", 96_000L, codedFrames - delay - padding)
            assertEquals("Exactly the original source frames decode, including the final marker", 96_000, samples.size)
            // SkipCutBuffer holds trailing padding, so buffer work timestamps
            // can differ from the continuous decoded PCM clock by that hold.
            // Require the exact source frame count above, and bound that
            // difference by the platform's declared padding, not a broad
            // duration tolerance. The chirp and final marker check fidelity.
            val paddingUs = padding.toLong() * 1_000_000 / 48_000
            assertTrue("Decoder start timestamp stays within declared padding: $firstDecodedTime",
                requireNotNull(firstDecodedTime) in 0..(paddingUs + 21))
            assertTrue("Decoder end timestamp stays within declared padding: $lastDecodedEnd",
                abs(lastDecodedEnd - 2_000_000) <= paddingUs + 21)
            var chirpDot = 0.0; var chirpEnergy = 0.0; var decodedEnergy = 0.0
            repeat(4800) { frame ->
                val seconds = frame / 48_000.0
                val reference = 4000 * sin(2 * PI * (220 * seconds + 7700 * seconds * seconds))
                val sample = samples[frame].toDouble()
                chirpDot += reference * sample
                chirpEnergy += reference * reference
                decodedEnergy += sample * sample
            }
            assertTrue("Source chirp starts at the first decoded frame",
                chirpDot / sqrt(chirpEnergy * decodedEnergy) > 0.98)
            // Measure a complete second away from encoder priming and tail.
            for (frequency in listOf(440, 660)) {
                var sine = 0.0; var cosine = 0.0
                repeat(48_000) { frame ->
                    val phase = frame * 2 * PI * frequency / 48_000
                    val sample = samples[24_000 + frame].toDouble()
                    sine += sample * sin(phase); cosine += sample * cos(phase)
                }
                assertTrue("$frequency Hz survives AAC export", 2 * hypot(sine, cosine) / 48_000 > 2000)
            }
            var tailSine = 0.0; var tailCosine = 0.0
            repeat(480) { offset ->
                val frame = 95_520 + offset
                val phase = frame * 2 * PI * 1100 / 48_000
                tailSine += samples[frame] * sin(phase)
                tailCosine += samples[frame] * cos(phase)
            }
            assertTrue("Final source tone survives within the original playback interval",
                2 * hypot(tailSine, tailCosine) / 480 > 2000)
        } catch (error: Throwable) {
            if (file.isFile) file.copyTo(File(directory.parentFile, "synthetic-aac-failed.m4a"), overwrite = true)
            throw error
        } finally {
            runCatching { decoder?.stop() }; decoder?.release(); extractor.release()
            directory.deleteRecursively()
        }
    }

    @Test fun failedOrDiscardedExportNeverLeavesARecording() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "synthetic-aac-failure-${System.nanoTime()}").also { check(it.mkdirs()) }
        try {
            val file = File(directory, "empty.m4a")
            val writer = AacRecordingFile(file)
            try { writer.close(); fail("Empty recording must fail") }
            catch (_: IllegalStateException) { assertFalse(file.exists()) }
            val discard = File(directory, "discard.m4a")
            AacRecordingFile(discard).apply { write(ShortArray(480)); discard() }
            assertFalse(discard.exists())
            val existing = File(directory, "existing.m4a").apply { writeText("keep") }
            try { AacRecordingFile(existing); fail("Must preserve existing output") }
            catch (_: IllegalStateException) { assertEquals("keep", existing.readText()) }
        } finally { directory.deleteRecursively() }
    }
}
