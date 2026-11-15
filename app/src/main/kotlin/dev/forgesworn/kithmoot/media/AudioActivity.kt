package dev.forgesworn.kithmoot.media

import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import java.util.IdentityHashMap

/** Only RMS values leave the media thread. No audio is retained. */
data class CallAudioActivity(val microphone: Double? = null, val incoming: Double? = null, val sample: Long = 0)
data class DeviceAudioActivity(val microphone: Double? = null, val incoming: Map<String, Double?> = emptyMap(), val sample: Long = 0)

/** Owned alongside a peer's receivers; detach before disposing that peer. */
internal class TrackAudioActivity {
    private class Input {
        val meter = LevelMeter()
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

    @Synchronized fun drain(allowed: List<AudioTrack>): Double? = inputs.mapNotNull { (track, input) ->
        val value = input.meter.drain()
        value.takeIf { allowed.any { it === track } }
    }.maxOrNull()

    @Synchronized fun close() { setTracks(emptyList()); closed = true }
}
