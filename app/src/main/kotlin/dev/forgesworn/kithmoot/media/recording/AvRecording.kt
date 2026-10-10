package dev.forgesworn.kithmoot.media.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.webrtc.EglBase
import java.io.File
import java.nio.ByteBuffer

/** A worker-owned AAC/H.264 export. PCM sample count drives both tracks, so
 * pause omits time without joining unrelated audio and video wall clocks.
 * [draw] must compose authorised call tracks into the current EGL surface;
 * it receives the exact 48 kHz sample position of the frame being exported.
 * The independent disk tracks are remuxed by timestamp, with bounded buffers.
 * Only a fully finalised two-track MP4 is returned. No upload is initiated.
 */
class AvRecordingFile(
    private val file: File,
    private val sharedContext: EglBase.Context?,
    private val draw: (sample: Long, width: Int, height: Int) -> Unit,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val releaseDraw: () -> Unit = {},
) : PcmFileOutput {
    private val parts = File(file.parentFile, file.name + ".parts")
    private val audioFile = File(parts, "audio.m4a")
    private val videoFile = File(parts, "video.mp4")
    private val audio: AacRecordingFile
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: android.view.Surface? = null
    private var egl: EglBase? = null
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var ended = false
    private var closed = false
    private var owner: Thread? = null
    private var samples = 0L
    private var nextVideoSample = 0L
    private var encodedVideoBytes = 0L
    private var videoFrames = 0L

    init {
        require(maxBytes >= 128 * 1024)
        check(file.createNewFile()) { "Recording destination already exists" }
        var ownParts = false
        try {
            check(parts.mkdir()) { "Recording temporary storage is unavailable" }
            ownParts = true
            audio = AacRecordingFile(audioFile, maxBytes)
        } catch (error: Throwable) {
            if (ownParts) parts.deleteRecursively()
            file.delete()
            throw error
        }
    }

    private fun assertWorker() {
        val current = Thread.currentThread()
        if (owner == null) owner = current
        check(owner === current) { "Audio/video export changed worker threads" }
        check(!current.isInterrupted) { "Audio/video export was interrupted" }
    }

    private fun initialiseVideo() {
        val madeCodec = MediaCodec.createByCodecName(checkNotNull(RecordingCodecs.videoEncoder) { "Video recording is unavailable on this device" })
        codec = madeCodec
        val format = RecordingCodecs.videoFormat()
        madeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = madeCodec.createInputSurface()
        madeCodec.start()
        muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        egl = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE).also {
            it.createSurface(requireNotNull(surface)); it.makeCurrent()
        }
    }

    override fun write(samples: ShortArray) {
        check(!closed)
        if (samples.isEmpty()) return
        assertWorker()
        try {
            if (codec == null) initialiseVideo()
            audio.write(samples)
            this.samples += samples.size
            while (nextVideoSample < this.samples) {
                requireNotNull(egl).makeCurrent()
                draw(nextVideoSample, WIDTH, HEIGHT)
                // The encoder input receives the audio clock, never wall time.
                requireNotNull(egl).swapBuffers(nextVideoSample * 1_000_000_000 / 48_000)
                nextVideoSample += SAMPLES_PER_FRAME
                drain(0)
            }
            checkSize()
        } catch (error: Throwable) {
            discard()
            throw error
        }
    }

    private fun checkSize() {
        // Reserve MP4 sample tables in addition to the encoder payload. Both
        // temporary tracks and the final export have explicit size bounds.
        check(encodedVideoBytes + audioFile.length() + 128 * 1024 + videoFrames * 32 <= maxBytes) {
            "Recording reached its file limit"
        }
    }

    private fun drain(waitUs: Long) {
        val encoder = requireNotNull(codec)
        val output = requireNotNull(muxer)
        while (!ended) {
            val index = encoder.dequeueOutputBuffer(info, waitUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(track < 0) { "Video format changed during recording" }
                    track = output.addTrack(encoder.outputFormat)
                    output.start()
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(track >= 0) { "Video encoder returned a frame before its format" }
                            encodedVideoBytes += info.size
                            videoFrames++
                            checkSize()
                            val buffer = requireNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            output.writeSampleData(track, buffer, info)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        assertWorker()
        try {
            check(samples > 0 && codec != null) { "No audio/video was recorded" }
            audio.close()
            requireNotNull(codec).signalEndOfInputStream()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!ended && System.nanoTime() < deadline) drain(10_000)
            check(ended && track >= 0 && videoFrames > 0) { "Video encoder did not finalise the recording" }
            val durationUs = samples * 1_000_000 / 48_000
            requireNotNull(muxer).writeSampleData(track, ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
                set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            })
            requireNotNull(muxer).stop()
            check(audioFile.length() + videoFile.length() <= maxBytes) { "Recording reached its file limit" }
            remux(durationUs)
            check(file.length() in 1..maxBytes) { "Recording exceeds its file limit" }
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally {
            release()
            audio.discard()
            parts.deleteRecursively()
        }
    }

    private fun remux(durationUs: Long) {
        val readers = listOf(MediaExtractor(), MediaExtractor())
        var combined: MediaMuxer? = null
        try {
            readers[0].setDataSource(audioFile.absolutePath)
            readers[1].setDataSource(videoFile.absolutePath)
            check(readers.all { it.trackCount == 1 }) { "Recording track is incomplete" }
            check(readers[0].getTrackFormat(0).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC)
            check(readers[1].getTrackFormat(0).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
            val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            combined = output
            val tracks = readers.map { output.addTrack(it.getTrackFormat(0)) }
            readers.forEach { it.selectTrack(0) }
            output.start()
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val sampleInfo = MediaCodec.BufferInfo()
            val last = longArrayOf(-1, -1)
            while (true) {
                check(!Thread.currentThread().isInterrupted) { "Recording finalisation was interrupted" }
                val next = readers.indices.filter { readers[it].sampleTime >= 0 }.minByOrNull { readers[it].sampleTime } ?: break
                val reader = readers[next]
                buffer.clear()
                val count = reader.readSampleData(buffer, 0)
                check(count in 1..buffer.capacity()) { "Invalid recording sample size" }
                val time = reader.sampleTime
                check(time >= last[next] && time <= durationUs) { "Invalid recording sample timestamp" }
                last[next] = time
                sampleInfo.set(0, count, time, reader.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                output.writeSampleData(tracks[next], buffer, sampleInfo)
                reader.advance()
            }
            check(last.all { it >= 0 }) { "Recording is missing a track" }
            tracks.forEach { destination ->
                sampleInfo.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                output.writeSampleData(destination, ByteBuffer.allocate(0), sampleInfo)
            }
            output.stop()
        } finally {
            readers.forEach { it.release() }
            combined?.release()
        }
    }

    override fun discard() {
        if (!closed) release()
        audio.discard()
        parts.deleteRecursively()
        file.delete()
    }

    private fun release() {
        closed = true
        if (egl != null) runCatching { egl?.makeCurrent(); releaseDraw() }
        runCatching { egl?.release() }; egl = null
        runCatching { surface?.release() }; surface = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { muxer?.release() }; muxer = null
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 15
        private const val SAMPLES_PER_FRAME = 48_000L / FRAME_RATE
    }
}
