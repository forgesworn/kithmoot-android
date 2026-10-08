package dev.forgesworn.kithmoot.ui.room

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.session.*

@Composable
fun PackEmoji(emoji: String, modifier: Modifier = Modifier) {
    if (!isCultEmoji(emoji)) { Text(emoji, modifier); return }
    val id = when (emoji) { ":600_facepalm:" -> R.drawable.cult_600_facepalm; ":600_moon:" -> R.drawable.cult_600_moon; ":600_laser:" -> R.drawable.cult_600_laser; else -> R.drawable.cult_600 }
    val moving = emoji in setOf(":600_spin:", ":600_rainbow:") && ValueAnimator.areAnimatorsEnabled()
    val turn = if (moving) {
        val animation = rememberInfiniteTransition(label = "600 emoji")
        animation.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "600 turn").value
    } else 0f
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(id), CULT_EMOJIS.first { it.first == emoji }.second,
            Modifier.fillMaxSize().graphicsLayer { rotationZ = if (emoji == ":600_spin:") turn else 0f },
            colorFilter = if (emoji == ":600_rainbow:") ColorFilter.colorMatrix(ColorMatrix(android.graphics.ColorMatrix().apply { setRotate(0, turn) }.array)) else null)
        val accent = when (emoji) { ":600_fire:" -> "🔥"; ":600_handshake:" -> "🤝"; else -> null }
        accent?.let { Text(it, Modifier.align(Alignment.BottomEnd)) }
    }
}

@Composable
fun PackMessageText(body: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    if (isCultEmoji(body.trim())) { PackEmoji(body.trim(), modifier.size(144.dp)); return }
    val codes = CULT_EMOJIS.map { it.first }
    val regex = remember { Regex(codes.joinToString("|", transform = Regex::escape)) }
    val text = AnnotatedString.Builder()
    var offset = 0
    for (match in regex.findAll(body)) {
        text.append(linkedMessage(body.substring(offset, match.range.first), MaterialTheme.colorScheme.primary))
        text.appendInlineContent(match.value, match.value); offset = match.range.last + 1
    }
    text.append(linkedMessage(body.substring(offset), MaterialTheme.colorScheme.primary))
    val inline = codes.associateWith { code -> InlineTextContent(Placeholder(28.sp, 28.sp, PlaceholderVerticalAlign.Center)) { PackEmoji(code, Modifier.fillMaxSize()) } }
    Text(text.toAnnotatedString(), modifier, style = style, color = color, inlineContent = inline)
}
