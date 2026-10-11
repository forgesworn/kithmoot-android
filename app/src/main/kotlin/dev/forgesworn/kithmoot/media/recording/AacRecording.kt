package dev.forgesworn.kithmoot.media.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Single-worker, disk-backed AAC/M4A export. Input timestamps come from the
 * mixer's sample count, so pauses and callback jitter cannot change the clock.
 * The caller owns an app-private destination and explicit Save/Discard.
 */
class AacRecordingFile(
    private val file: File,
    private val maxBytes: Long = 256L * 1024 * 1024,
) : PcmFileOutput {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var frames = 0L
    private var encodedBytes = 0L
    private var track = -1
    private var ended = false
    private var closed = false
    private var firstTimestamp: Long? = null
    // Keep the final AAC access unit until close so its incomplete PCM can be
    // zero-padded. Some Android encoders otherwise drop that partial unit.
    private val pending = ShortArray(1024)
    private var pendingCount = 0

    init {
        require(maxBytes >= 64 * 1024)
        check(file.createNewFile()) { "Recording destination already exists" }
        var madeCodec: MediaCodec? = null
        var madeMuxer: MediaMuxer? = null
        try {
            madeCodec = MediaCodec.createByCodecName(checkNotNull(RecordingCodecs.audioEncoder) { "AAC recording is unavailable on this device" })
            val format = RecordingCodecs.audioFormat()
            madeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            madeCodec.start()
            madeMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec = madeCodec
            muxer = madeMuxer
        } catch (error: Throwable) {
            runCatching { madeMuxer?.release() }
            runCatching { madeCodec?.release() }
            file.delete()
            throw error
        }
    }

    override fun write(samples: ShortArray) {
        check(!closed)
        var offset = 0
        while (offset < samples.size) {
            if (pendingCount == pending.size) queuePending(end = false)
            val count = minOf(samples.size - offset, pending.size - pendingCount)
            samples.copyInto(pending, pendingCount, offset, offset + count)
            pendingCount += count
            offset += count
        }
    }

    private fun queuePending(end: Boolean) {
        check(pendingCount > 0)
        var offset = 0
        while (offset < pendingCount) {
            val index = inputBuffer()
            val buffer = requireNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
            buffer.clear()
            val count = minOf(pendingCount - offset, buffer.remaining() / 2)
            check(count > 0) { "AAC encoder supplied an empty input buffer" }
            repeat(count) { buffer.putShort(pending[offset + it]) }
            val flags = if (end && offset + count == pendingCount) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            codec.queueInputBuffer(index, 0, count * 2, timestamp(), flags)
            offset += count
            frames += count
            drain(0)
        }
        pending.fill(0)
        pendingCount = 0
    }

    private fun timestamp() = frames * 1_000_000 / 48_000

    private fun inputBuffer(): Int {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(0)
        }
        error("AAC encoder stopped accepting audio")
    }

    private fun drain(waitUs: Long) {
        while (!ended) {
            val index = codec.dequeueOutputBuffer(info, waitUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(track < 0) { "AAC format changed during recording" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(track >= 0) { "AAC encoder returned audio before its format" }
                            // Reserve space for MP4 tables before hitting the source limit.
                            encodedBytes += info.size
                            check(encodedBytes + 64 * 1024 + frames / 1024 * 32 <= maxBytes) { "Recording reached its file limit" }
                            val buffer = requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val first = firstTimestamp ?: info.presentationTimeUs.also { firstTimestamp = it }
                            info.presentationTimeUs = (info.presentationTimeUs - first).coerceAtLeast(0)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        try {
            check(pendingCount > 0) { "No audio was recorded" }
            val sourceEnd = (frames + pendingCount) * 1_000_000 / 48_000
            pending.fill(0, pendingCount, pending.size)
            pendingCount = pending.size
            queuePending(end = true)
            val deadline = System.nanoTime() + TIMEOUT_NANOS
            while (!ended && System.nanoTime() < deadline) drain(10_000)
            check(ended && track >= 0) { "AAC encoder did not finalise the recording" }
            // Android's MP4 muxer uses an empty EOS sample to set the final
            // access unit's duration to the original PCM clock, without
            // extending playback by the zero padding.
            val finalInfo = MediaCodec.BufferInfo().apply { set(0, 0, sourceEnd, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            muxer.writeSampleData(track, java.nio.ByteBuffer.allocate(0), finalInfo)
            muxer.stop()
            check(file.length() in 1..maxBytes) { "Recording exceeds its file limit" }
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally { release() }
    }

    override fun discard() { try { if (!closed) release() } finally { file.delete() } }

    private fun release() {
        closed = true
        pending.fill(0)
        pendingCount = 0
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    companion object { private const val TIMEOUT_NANOS = 5_000_000_000L }
}
