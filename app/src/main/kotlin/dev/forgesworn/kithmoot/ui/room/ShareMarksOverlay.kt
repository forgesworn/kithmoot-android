package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fading marks drawn on one screen share, painted over its video.
 *
 * [LiveMark] coordinates are zero to one in the shared image (see
 * docs/protocol.md, "Call signalling profile 1"), so they are scaled to
 * whatever size this overlay is given here rather than carrying a size of
 * their own - it lines up with the video pane beneath it without either one
 * knowing the other's exact pixel size.
 */
@Composable
fun ShareMarksOverlay(marks: List<LiveMark>, modifier: Modifier = Modifier) {
    if (marks.isEmpty()) return
    Canvas(modifier = modifier) {
        val haloWidth = 7.dp.toPx()
        val lineWidth = 3.5.dp.toPx()
        for (mark in marks) {
            val points = mark.annotation.points ?: continue
            if (points.size < 2) continue
            val path = Path()
            points.forEachIndexed { index, point ->
                val offset = Offset(point.x.toFloat() * size.width, point.y.toFloat() * size.height)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            val colour = parseMarkColour(mark.color)
            // A dark halo under a fairly light, saturated line, so a stroke
            // stays legible over arbitrary screen content: a slide, a
            // terminal, a video tile. Matches the web client's paintStroke.
            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.55f * mark.alpha),
                style = Stroke(width = haloWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                path = path,
                color = colour.copy(alpha = mark.alpha),
                style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        // One small name label per author, near the end of their most recent
        // live stroke - enough to say who is pointing without cluttering a
        // tile-sized share preview.
        val latestByAuthor = marks.groupBy { it.author.key }.mapValues { (_, strokes) -> strokes.last() }
        val labelSizePx = 11.sp.toPx()
        for (mark in latestByAuthor.values) {
            val last = mark.annotation.points?.lastOrNull() ?: continue
            val x = last.x.toFloat() * size.width
            val y = (last.y.toFloat() * size.height - 10.dp.toPx()).coerceAtLeast(labelSizePx)
            drawContext.canvas.nativeCanvas.drawText(
                mark.author.label,
                x,
                y,
                android.graphics.Paint().apply {
                    color = parseMarkColour(mark.color).toArgb()
                    alpha = (mark.alpha * 255).toInt().coerceIn(0, 255)
                    textSize = labelSizePx
                    isAntiAlias = true
                    setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                },
            )
        }
    }
}

private fun parseMarkColour(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Yellow)
