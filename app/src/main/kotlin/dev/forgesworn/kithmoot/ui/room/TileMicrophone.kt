package dev.forgesworn.kithmoot.ui.room

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgesworn.kithmoot.media.AudioReading
import dev.forgesworn.kithmoot.media.CallAudioActivity

internal data class TileMicrophoneReading(val reading: AudioReading = AudioReading(null), val status: String? = null)

/** A tile follows its mic owner, never the loudest device or last speaker. */
internal fun tileMicrophoneReading(tile: ParticipantTile, levels: CallAudioActivity, roomId: String,
    selfDevice: String, micOn: Boolean, micMuted: Boolean): TileMicrophoneReading {
    val local = tile.isSelf && tile.micIsThisDevice && tile.micDevice == selfDevice
    val status = when {
        local && !micOn -> "Off"
        local && micMuted || tile.micMuted -> "Muted"
        !tile.hasMic -> "Off"
        tile.isSilencedForYou -> "Silenced for you"
        else -> null
    }
    if (status != null) return TileMicrophoneReading(status = status)
    if (levels.roomId != null && levels.roomId != roomId) return TileMicrophoneReading()
    val reading = if (local) AudioReading(levels.microphone, levels.microphoneWaveform)
        else levels.microphones[tile.micDevice] ?: AudioReading(null)
    return TileMicrophoneReading(reading)
}

/** Collect only inside this strip: audio samples cannot rebuild the video surface. */
@Composable
internal fun TileMicrophone(tile: ParticipantTile, context: CallTileContext, modifier: Modifier = Modifier) {
    val levels = context.audioActivity?.collectAsStateWithLifecycle()?.value ?: CallAudioActivity()
    val signal = tileMicrophoneReading(tile, levels, context.roomId, context.selfDevice, context.micOn, context.micMuted)
    AudioSignal("${context.nameOf(tile)} microphone", signal.reading.level, levels.sample, context.roomId,
        signal.status, modifier, waveform = signal.reading.waveform, compact = true, showLabel = false,
        textColor = if (tile.participant in context.speaking) MaterialTheme.colorScheme.onPrimary else Color.White)
}
