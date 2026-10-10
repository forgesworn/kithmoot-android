package dev.forgesworn.kithmoot.media.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES20
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class AvRecordingTest {
    @Test fun decoded_audio_and_video_transitions_share_the_sample_clock() {
        val directory = fixtureDirectory()
        val file = File(directory, "synthetic.mp4")
        try {
            val rendered = mutableListOf<Long>()
            val writer = AvRecordingFile(file, null, { sample, width, height ->
                rendered += sample
                GLES20.glViewport(0, 0, width, height)
                val value = if ((sample / 24_000) % 2 == 0L) 0f else 1f
                GLES20.glClearColor(value, value, value, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            })
            try {
                repeat(200) { block ->
                    writer.write(ShortArray(480) { offset ->
                        val sample = block * 480 + offset
                        if ((sample / 24_000) % 2 == 0) 0
                        else (5000 * sin(sample * 2 * PI * 440 / 48_000)).toInt().toShort()
                    })
                }
                writer.close()
            } finally { if (!file.isFile || file.length() == 0L) writer.discard() }
            assertEquals((0L until 96_000L step 3200).toList(), rendered)
            assertTrue(file.length() in 1..256L * 1024 * 1024)
            assertFalse(File(directory, file.name + ".parts").exists())

            val audio = ArrayList<Short>()
            val video = mutableListOf<Pair<Long, Int>>()
            decode(file, MediaFormat.MIMETYPE_AUDIO_AAC) { codec, index, info ->
                val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                while (buffer.remaining() >= 2) audio += buffer.short
            }
            decode(file, MediaFormat.MIMETYPE_VIDEO_AVC) { codec, index, info ->
                if (info.size > 0) requireNotNull(codec.getOutputImage(index)).use { image ->
                    val plane = image.planes[0]
                    var total = 0
                    for (y in 0 until 16) for (x in 0 until 16) {
                        val position = plane.buffer.position() + (image.height / 2 + y) * plane.rowStride +
                            (image.width / 2 + x) * plane.pixelStride
                        total += plane.buffer.get(position).toInt() and 255
                    }
                    video += info.presentationTimeUs to total / 256
                }
            }
            assertTrue("Complete audio track decoded: ${audio.size}", audio.size >= 96_000)
            assertEquals("Complete video track decoded", 30, video.size)
            val audioWindows = (0 until audio.size / 480).map { block ->
                sqrt((0 until 480).sumOf { offset -> audio[block * 480 + offset].toDouble().pow(2) } / 480) > 1000
            }
            val audioEdges = audioWindows.indices.drop(1).filter { audioWindows[it] != audioWindows[it - 1] }
                .map { it * 10_000L }
            val videoEdges = video.indices.drop(1).filter { (video[it].second > 128) != (video[it - 1].second > 128) }
                .map { video[it].first }
            assertEquals("Three audible transitions", 3, audioEdges.size)
            assertEquals("Three visible transitions", 3, videoEdges.size)
            audioEdges.zip(videoEdges).forEach { (sound, picture) ->
                assertTrue("Decoded A/V transition offset: audio=$sound video=$picture", abs(sound - picture) <= 80_000)
            }
            android.util.Log.i("KithMootRecordingTest", "AV audioSamples=${audio.size} videoFrames=${video.size} audioEdges=$audioEdges videoEdges=$videoEdges bytes=${file.length()}")
        } catch (error: Throwable) {
            if (file.isFile && file.length() > 0) file.copyTo(File(directory.parentFile, "synthetic-av-failed.mp4"), true)
            throw error
        } finally { directory.deleteRecursively() }
    }

    @Test fun empty_discarded_and_over_limit_exports_are_removed_and_existing_files_survive() {
        val directory = fixtureDirectory()
        try {
            val empty = File(directory, "empty.mp4")
            try { AvRecordingFile(empty, null, { _, _, _ -> }).close(); fail("Empty recording must fail") }
            catch (_: IllegalStateException) { assertFalse(empty.exists()) }
            val discard = File(directory, "discard.mp4")
            AvRecordingFile(discard, null, { _, _, _ -> }).discard()
            assertFalse(discard.exists())
            val existing = File(directory, "existing.mp4").apply { writeText("keep") }
            try { AvRecordingFile(existing, null, { _, _, _ -> }); fail("Must preserve an existing file") }
            catch (_: IllegalStateException) { assertEquals("keep", existing.readText()) }
            val limited = File(directory, "limited.mp4")
            val writer = AvRecordingFile(limited, null, { _, width, height ->
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(1f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }, maxBytes = 128 * 1024)
            try { writer.write(ShortArray(4800)); writer.close(); fail("File reserve must reject an over-limit export") }
            catch (_: IllegalStateException) { assertFalse(limited.exists()) }
            finally { writer.discard() }
            assertEquals(listOf("existing.mp4"), directory.listFiles()!!.map { it.name }.sorted())
        } finally { directory.deleteRecursively() }
    }

    private fun fixtureDirectory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "synthetic-av-${System.nanoTime()}").also { check(it.mkdirs()) }

    private fun decode(file: File, mime: String, accept: (MediaCodec, Int, MediaCodec.BufferInfo) -> Unit) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals("Exactly one audio and one video track", 2, extractor.trackCount)
            val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime }
            val format = extractor.getTrackFormat(track)
            assertTrue("Original two-second duration for $mime: $format", abs(format.getLong(MediaFormat.KEY_DURATION) - 2_000_000) < 1000)
            if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime).also { decoder = it }
            codec.configure(format, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!outputEnded && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    try { accept(codec, index, info); outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 }
                    finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            assertTrue("Complete $mime track must decode", outputEnded)
        } finally {
            runCatching { decoder?.stop() }; decoder?.release(); extractor.release()
        }
    }
}
