package dev.forgesworn.kithmoot.ui.room

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.CountdownStage
import dev.forgesworn.kithmoot.session.countdown
import dev.forgesworn.kithmoot.session.countdownAccessibleName
import dev.forgesworn.kithmoot.session.finalBannerText
import dev.forgesworn.kithmoot.session.stageAnnouncement
import dev.forgesworn.kithmoot.ui.epochSeconds
import kotlinx.coroutines.delay

/**
 * The countdown: green, amber, red, then the final minute, for a room that
 * self-destructs; the same pill in neutral grey ("Ends in…") for one that ends
 * and keeps a read-only copy, so the colours mean destruction only. Never
 * colour alone: every pill carries words and a fuse. The colours are the
 * theme's own container pairs, chosen for contrast in both themes (amber on a
 * light ground has dark text). The arithmetic is session/SelfDestruct.kt.
 */
internal data class PillColours(val container: Color, val content: Color)

@Composable
internal fun pillColours(stage: CountdownStage, destruct: Boolean): PillColours {
    val scheme = MaterialTheme.colorScheme
    if (!destruct) return PillColours(scheme.surfaceVariant, scheme.onSurfaceVariant)
    return when (stage) {
        CountdownStage.GREEN -> PillColours(scheme.primaryContainer, scheme.onPrimaryContainer)
        CountdownStage.AMBER -> PillColours(scheme.secondaryContainer, scheme.onSecondaryContainer)
        CountdownStage.RED, CountdownStage.FINAL, CountdownStage.GONE -> PillColours(scheme.errorContainer, scheme.onErrorContainer)
    }
}

/** Unix seconds, ticking once a second on the second while shown. */
@Composable
internal fun rememberNow(): Long {
    val now by produceState(epochSeconds()) {
        while (true) {
            delay(1_000 - System.currentTimeMillis() % 1_000)
            value = epochSeconds()
        }
    }
    return now
}

/** Android's "Remove animations": animator scale off. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** A fuse: a curling cord ending in a spark, in the text's own colour. */
@Composable
private fun FuseIcon(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        val s = size.width / 16f
        val cord = Path().apply {
            moveTo(2 * s, 14 * s)
            cubicTo(5 * s, 14 * s, 5 * s, 10 * s, 8 * s, 10 * s)
            cubicTo(11 * s, 10 * s, 11 * s, 6 * s, 13 * s, 5 * s)
        }
        drawPath(cord, colour, style = Stroke(width = 1.6f * s, cap = StrokeCap.Round))
        val spark = Path().apply {
            moveTo(13 * s, 1.5f * s); lineTo(13.7f * s, 3.3f * s); lineTo(15.5f * s, 4f * s); lineTo(13.7f * s, 4.7f * s)
            lineTo(13 * s, 6.5f * s); lineTo(12.3f * s, 4.7f * s); lineTo(10.5f * s, 4f * s); lineTo(12.3f * s, 3.3f * s); close()
        }
        drawPath(spark, colour)
    }
}

/**
 * A countdown pill for a room ending at [endsAt]. Its accessible name is the
 * words in full ("Self-destructs in 5 hours 12 minutes"), changing only by the
 * minute once the clock ticks.
 */
@Composable
internal fun CountdownPill(endsAt: Long, startsAt: Long?, destruct: Boolean, now: Long, modifier: Modifier = Modifier) {
    val c = countdown(endsAt, startsAt, destruct, now)
    val colours = pillColours(c.stage, destruct)
    val name = countdownAccessibleName(endsAt, startsAt, destruct, now)
    Row(
        modifier
            .background(colours.container, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FuseIcon(colours.content)
        Text(
            c.text,
            color = colours.content,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

/**
 * The room's countdown line under its header: the pill, and TalkBack told
 * when a self-destructing room enters amber, red and its final minute (never
 * on every tick, and not for the stage it opened in).
 */
@Composable
internal fun RoomCountdownLine(endsAt: Long, startsAt: Long?, destruct: Boolean, modifier: Modifier = Modifier) {
    // Read here, not by the caller, so only this line recomposes as it ticks.
    val now = rememberNow()
    val stage = countdown(endsAt, startsAt, destruct, now).stage
    val view = LocalView.current
    var shownStage by remember(endsAt) { mutableStateOf<CountdownStage?>(null) }
    LaunchedEffect(stage, destruct) {
        val before = shownStage
        shownStage = stage
        if (!destruct || before == null || before == stage) return@LaunchedEffect
        stageAnnouncement(stage, endsAt - epochSeconds())?.let { view.announceForAccessibility(it) }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CountdownPill(endsAt, startsAt, destruct, now)
    }
}

/**
 * The final minute's banner across the chat: "This room self-destructs in
 * 0:45. Save anything you need now." A slow pulse, unless Android's remove
 * animations is on. Not a live region: the stage change already said so, and
 * the seconds must not be read out one by one, so its name holds still.
 */
@Composable
internal fun FinalMinuteBanner(endsAt: Long, modifier: Modifier = Modifier) {
    val now = rememberNow()
    if (endsAt - now !in 1..dev.forgesworn.kithmoot.session.FINAL_SECONDS) return
    val reduceMotion = rememberReduceMotion()
    val pulse = if (reduceMotion) 1f else {
        val transition = rememberInfiniteTransition(label = "final minute")
        val value by transition.animateFloat(1f, 0.72f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "pulse")
        value
    }
    val colours = pillColours(CountdownStage.FINAL, destruct = true)
    Row(
        modifier.fillMaxWidth()
            .background(colours.container.copy(alpha = colours.container.alpha * pulse))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = "This room self-destructs in under a minute. Save anything you need now." },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FuseIcon(colours.content)
        Text(finalBannerText(endsAt - now), color = colours.content, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
