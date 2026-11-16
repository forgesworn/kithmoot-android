package dev.forgesworn.kithmoot.ui.room

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgesworn.kithmoot.media.CallAudioActivity
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/** Live PCM peaks, with a compact overlay in landscape. Never announces over callers. */
@Composable
fun AudioActivityPanel(activity: StateFlow<CallAudioActivity>?, scope: String, micOn: Boolean, micMuted: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val levels = activity?.collectAsStateWithLifecycle()?.value ?: CallAudioActivity()
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AudioSignal("Your mic", levels.microphone, levels.sample, scope,
            when { !micOn -> "Off"; micMuted -> "Muted"; else -> null }, Modifier.weight(1f), waveform = levels.microphoneWaveform, compact = compact)
        AudioSignal("Incoming audio", levels.incoming, levels.sample, scope, null, Modifier.weight(1f), waveform = levels.incomingWaveform, compact = compact)
    }
}

@Composable
internal fun AudioSignal(label: String, level: Double?, sample: Long, scope: String, override: String?, modifier: Modifier = Modifier,
    reducedMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(), waveform: List<Float> = emptyList(), compact: Boolean = false) {
    val value = level?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    val state = override ?: when { value == null -> "Waiting"; value >= 0.005 -> "Sound"; else -> "Quiet" }
    val height = if (override == null && value != null) (sqrt(value) * 2).coerceAtMost(1.0).toFloat() else 0f
    val ink = if (state == "Sound") Color(0xFF52D5FF) else Color.White
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = state
    }.testTag("audio-$label")) {
        // Reserve both lines: "Incoming audio: Waiting" wraps on narrow
        // phones where "Incoming audio: Sound" fits on one. Its neighbours
        // include a native video surface, which must not resize per sample.
        Text("$label: $state", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium, color = if (compact) Color.White else Color.Unspecified,
            minLines = if (compact) 1 else 2, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        Canvas(Modifier.fillMaxWidth().height(if (compact) 14.dp else 24.dp).background(Color(0xFF1B1B1F))
            .testTag(if (reducedMotion) "audio-level" else "audio-trace")) {
            if (reducedMotion) {
                drawRect(Color.White, topLeft = Offset(0f, size.height / 3), size = Size(size.width, size.height / 3))
                drawRect(ink, topLeft = Offset(0f, size.height / 3), size = Size(size.width * height, size.height / 3))
            } else {
                drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                if (state == "Sound" && waveform.size > 1) {
                    val path = Path()
                    val peaks = waveform.take(256)
                    peaks.forEachIndexed { i, peak ->
                        val value = if (peak.isFinite()) (peak * 4).coerceIn(-1f, 1f) else 0f
                        val x = size.width * i / (peaks.size - 1)
                        val y = size.height * (0.5f - value * 0.45f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, ink, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
    }
}
