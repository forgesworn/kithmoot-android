package dev.forgesworn.kithmoot.ui.room

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgesworn.kithmoot.media.CallAudioActivity
import dev.forgesworn.kithmoot.ui.RoomState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/** Named microphones stay visible when the gallery is collapsed or chat is open. */
@Composable
internal fun AudioActivityPanel(activity: StateFlow<CallAudioActivity>?, call: RoomState) {
    val levels = activity?.collectAsStateWithLifecycle()?.value ?: CallAudioActivity()
    val self = call.tiles.firstOrNull { it.isSelf }
        ?: ParticipantTile(call.selfParticipant, true, 1, emptyList(), null, false)
    val onCall = call.currentCall?.participants.orEmpty().toSet()
    val callers = listOf(self) + call.tiles.filter { !it.isSelf && it.participant in onCall }
    val profiles = if (call.profilesEnabled) call.profiles else emptyMap()
    val collisions = sameNamed(callers, profiles)
    val gap = 6.dp
    val inset = 12.dp
    val minWidth = 144.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = ((maxWidth - inset * 2 - gap * (callers.size - 1)) / callers.size).coerceAtLeast(minWidth)
        LazyRow(
            Modifier.fillMaxWidth().testTag("participant-microphones"),
            contentPadding = PaddingValues(horizontal = inset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(callers, key = { it.participant }) { tile ->
                val label = if (tile.isSelf) "You" else ownName(tile, profiles, tile.participant)
                    ?.let { keyedName(it, tile.participant, collisions) } ?: NAMELESS
                val signal = tileMicrophoneReading(tile, levels, call.roomId, call.selfDevice, call.micOn, call.micMuted)
                AudioSignal(label, signal.reading.level, signal.status, Modifier.width(width),
                    waveform = signal.reading.waveform)
            }
        }
    }
}

@Composable
internal fun AudioSignal(label: String, level: Double?, status: String?, modifier: Modifier = Modifier,
    reducedMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(), waveform: List<Float> = emptyList(),
    showLabel: Boolean = true, textColor: Color = Color.Unspecified) {
    val value = level?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    val state = status ?: when { value == null -> "Waiting"; value >= 0.005 -> "Speaking"; else -> "Quiet" }
    val speaking = state == "Speaking"
    val height = if (status == null && value != null) (sqrt(value) * 2).coerceAtMost(1.0).toFloat() else 0f
    val ink = if (speaking) Color(0xFF52D5FF) else Color.White
    val shape = RoundedCornerShape(8.dp)
    val frame = if (showLabel) Modifier.background(MaterialTheme.colorScheme.surfaceContainer, shape)
        .border(1.dp, if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .padding(horizontal = 6.dp, vertical = 4.dp) else Modifier
    Column(modifier.then(frame).semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = state
    }.testTag("audio-$label")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showLabel) Text(label, Modifier.weight(1f).clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (showLabel && state == "Silenced for you") "Silenced" else state, Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.labelMedium,
                color = if (showLabel) MaterialTheme.colorScheme.onSurfaceVariant else textColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Canvas(Modifier.fillMaxWidth().height(if (showLabel) 18.dp else 14.dp).background(Color(0xFF1B1B1F), RoundedCornerShape(3.dp))
            .testTag(if (reducedMotion) "audio-level" else "audio-trace")) {
            if (reducedMotion) {
                drawRect(Color.White.copy(alpha = 0.15f), topLeft = Offset(0f, size.height / 3), size = Size(size.width, size.height / 3))
                drawRect(ink, topLeft = Offset(0f, size.height / 3), size = Size(size.width * height, size.height / 3))
            } else {
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                if (speaking && waveform.size > 1) {
                    val path = Path()
                    val peaks = waveform.take(256)
                    peaks.forEachIndexed { i, peak ->
                        val value = if (peak.isFinite()) (peak * 4).coerceIn(-1f, 1f) else 0f
                        val x = size.width * i / (peaks.size - 1)
                        val y = size.height * (0.5f - value * 0.45f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, ink.copy(alpha = 0.12f), style = Stroke(width = 4.dp.toPx()))
                    drawPath(path, ink, style = Stroke(width = 1.2.dp.toPx()))
                }
            }
        }
    }
}
