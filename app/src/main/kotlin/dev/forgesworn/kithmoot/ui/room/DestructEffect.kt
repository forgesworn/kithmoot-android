package dev.forgesworn.kithmoot.ui.room

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

/** A brief burst after local cleanup, independent of the cleanup coroutine. */
@Composable
internal fun DestructEffect(onFinished: () -> Unit) {
    val reduced = rememberReduceMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) delay(3200) else { progress.animateTo(1f, tween(2200)); delay(1000) }
        onFinished()
    }
    val ink = Color(0xffffcf92)
    Box(Modifier.fillMaxSize().background(Color(0xff101114).copy(alpha = .96f)), contentAlignment = Alignment.Center) {
        if (!reduced) Canvas(Modifier.fillMaxSize()) {
            val p = progress.value
            val origin = Offset(size.width / 2, size.height * .40f)
            val radius = if (p < .3f) 52.dp.toPx() * (1f - p * 2.6f) else 52.dp.toPx() * (1f + (p - .3f) * 4f)
            drawCircle(ink.copy(alpha = (1 - p).coerceIn(0f, 1f)), radius, origin, style = Stroke(2.dp.toPx()))
            val burst = ((p - .2f) / .8f).coerceIn(0f, 1f)
            for (i in 0 until 32) {
                val angle = Math.toRadians(i * 137.508)
                val distance = (25 + burst * (65 + i % 5 * 24)).dp.toPx()
                val point = origin + Offset(cos(angle).toFloat() * distance, sin(angle).toFloat() * distance)
                drawCircle(ink.copy(alpha = (sin(burst * PI).toFloat()).coerceIn(0f, 1f)), (2 + i % 2).dp.toPx(), point)
            }
        }
        Column(Modifier.padding(24.dp).offset(y = 80.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Room self-destructed", color = ink, style = MaterialTheme.typography.headlineMedium)
            Text("The room is gone from this device", color = Color(0xfff2e3d4), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
