package dev.forgesworn.kithmoot.media

import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import java.util.IdentityHashMap

/** Live levels and bounded peak snapshots, replaced each poll; never saved or sent. */
data class CallAudioActivity(val microphone: Double? = null, val incoming: Double? = null, val sample: Long = 0,
    val microphoneWaveform: List<Float> = emptyList(), val incomingWaveform: List<Float> = emptyList())
data class DeviceAudioActivity(val microphone: Double? = null, val incoming: Map<String, Double?> = emptyMap(), val sample: Long = 0,
    val microphoneWaveform: List<Float> = emptyList(), val incomingWaveforms: Map<String, List<Float>> = emptyMap())
internal data class AudioReading(val level: Double?, val waveform: List<Float> = emptyList())

/** The latest PCM block reduced to 128 min/max pairs. No accumulating audio buffer. */
internal class WaveformMeter {
    private val level = LevelMeter()
    private var peaks = emptyList<Float>()
    @Synchronized fun addPcm16(buffer: java.nio.ByteBuffer, bytes: Int) {
        level.addPcm16(buffer, bytes)
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = (minOf(bytes, view.limit()).coerceAtLeast(0) / 2).coerceAtMost(4096)
        val buckets = minOf(128, count)
        peaks = buildList(buckets * 2) {
            for (bucket in 0 until buckets) {
                var low = 1f; var high = -1f
                for (i in bucket * count / buckets until (bucket + 1) * count / buckets) {
                    val value = view.getShort(i * 2) / 32768f
                    low = minOf(low, value); high = maxOf(high, value)
                }
                add(low); add(high)
            }
        }
    }
    @Synchronized fun drain(): AudioReading {
        val rms = level.drain()
        val trace = if (rms != null) peaks else emptyList()
        peaks = emptyList()
        return AudioReading(rms, trace)
    }
}

/** Owned alongside a peer's receivers; detach before disposing that peer. */
internal class TrackAudioActivity {
    private class Input {
        val meter = WaveformMeter()
        @Volatile var active = true
        val sink = AudioTrackSink { buffer, bits, _, channels, frames, _ ->
            if (active && bits == 16 && channels > 0 && frames > 0) meter.addPcm16(buffer, frames * channels * 2)
        }
    }
    private val inputs = IdentityHashMap<AudioTrack, Input>()
    private var closed = false

    // Owner-thread operations, as with the recording adapter. Native removal
    // can wait for a callback, so callbacks never acquire an owner lock.
    @Synchronized fun setTracks(tracks: List<AudioTrack>) {
        if (closed) return
        for (track in inputs.keys.toList()) if (tracks.none { it === track }) {
            val input = inputs.remove(track)!!
            input.active = false
            runCatching { track.removeSink(input.sink) }
        }
        for (track in tracks) if (!inputs.containsKey(track)) {
            val input = Input()
            inputs[track] = input
            runCatching { track.addSink(input.sink) }.onFailure {
                input.active = false
                inputs.remove(track)
            }
        }
    }

    @Synchronized fun drain(allowed: List<AudioTrack>): AudioReading? = inputs.mapNotNull { (track, input) ->
        val value = input.meter.drain()
        value.takeIf { it.level != null && allowed.any { it === track } }
    }.maxByOrNull { it.level ?: 0.0 }

    @Synchronized fun close() { setTracks(emptyList()); closed = true }
}
