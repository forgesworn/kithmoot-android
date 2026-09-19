package dev.forgesworn.kithmoot.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One audio input for WebRTC: optional microphone plus consented app playback.
 * KithMoot's own output is excluded so listeners are never looped back.
 */
class PlaybackAudio {
    @Volatile var microphone = false
    private var recorder: AudioRecord? = null
    private val playback = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
    val active: Boolean @Synchronized get() = recorder != null

    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO before starting.
    @Synchronized
    fun start(projection: MediaProjection) {
        if (recorder != null) return
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid()).build()
        val format = AudioFormat.Builder().setSampleRate(48_000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val made = AudioRecord.Builder().setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(9_600, AudioRecord.getMinBufferSize(48_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setAudioPlaybackCaptureConfig(config).build()
        try {
            check(made.state == AudioRecord.STATE_INITIALIZED) { "Android could not capture app sound." }
            made.startRecording()
            check(made.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            recorder = made
        } catch (error: Exception) { made.release(); throw error }
    }

    @Synchronized
    fun stop() { recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null }

    /** Always overwrite the input when the microphone is off, even on failure. */
    @Synchronized
    fun fill(buffer: ByteBuffer, format: Int, channels: Int, rate: Int, bytes: Int) {
        val length = minOf(bytes, buffer.capacity())
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val mic = microphone
        if (!mic) for (i in 0 until length) input.put(i, 0)
        if (format != AudioFormat.ENCODING_PCM_16BIT || channels != 1 || rate != 48_000 || length > playback.capacity()) return
        val record = recorder ?: return
        playback.clear()
        val read = runCatching { record.read(playback, length, AudioRecord.READ_NON_BLOCKING) }.getOrDefault(0)
        for (i in 0 until (read.coerceAtLeast(0) / 2)) {
            val offset = i * 2
            input.putShort(offset, mixPcm(input.getShort(offset), playback.getShort(offset)))
        }
    }
}

internal fun mixPcm(microphone: Short, playback: Short): Short =
    (microphone.toInt() + playback.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
