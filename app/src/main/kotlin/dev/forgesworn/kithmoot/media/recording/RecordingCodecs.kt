package dev.forgesworn.kithmoot.media.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** Probe the exact export formats before offering video capture. Codec
 * availability is stable within this process; runtime resource exhaustion
 * remains an explicit recording failure, with local cleanup. */
object RecordingCodecs {
    fun audioFormat() = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
    }

    fun videoFormat() = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, AvRecordingFile.WIDTH, AvRecordingFile.HEIGHT).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, 640_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, AvRecordingFile.FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }

    val audioEncoder: String? by lazy { runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(audioFormat()) }.getOrNull() }
    val videoEncoder: String? by lazy { runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoFormat()) }.getOrNull() }
    val videoSupported: Boolean get() = audioEncoder != null && videoEncoder != null
}
