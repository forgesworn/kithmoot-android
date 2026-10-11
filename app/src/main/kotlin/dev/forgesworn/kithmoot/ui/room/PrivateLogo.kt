package dev.forgesworn.kithmoot.ui.room

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.forgesworn.kithmoot.protocol.LogoImage
import dev.forgesworn.kithmoot.protocol.LogoImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** Decode validated local bytes only. A malformed or invisible image keeps initials. */
internal fun decodePrivateLogo(image: LogoImage): Bitmap? {
    val bytes = LogoImages.bytes(image.toJson()) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth != image.width || bounds.outHeight != image.height) return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
    if (bitmap.width != image.width || bitmap.height != image.height) { bitmap.recycle(); return null }
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
        if (row.any { it ushr 24 != 0 }) return bitmap
    }
    bitmap.recycle()
    return null
}

private data class DecodedPrivateLogo(val hash: String, val bitmap: Bitmap)

/** Names are supplied by adjacent visible text. No URL or network image loader exists. */
@Composable
internal fun PrivateLogo(image: LogoImage?, name: String, modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.primaryContainer,
    ink: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    var decoded by remember { mutableStateOf<DecodedPrivateLogo?>(null) }
    LaunchedEffect(image?.sha256) {
        decoded = null
        if (image == null) return@LaunchedEffect
        var owned: Bitmap? = null
        try {
            withContext(Dispatchers.Default) { owned = decodePrivateLogo(image) }
            coroutineContext.ensureActive()
            owned?.let { decoded = DecodedPrivateLogo(image.sha256, it); owned = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { decoded = null }
        finally { owned?.recycle() }
    }
    // A completed decode for the old key is never shown during replacement.
    val bitmap = decoded?.takeIf { it.hash == image?.sha256 }?.bitmap
    Box(modifier.clip(CircleShape).background(fill).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
        else {
            val initial = String(Character.toChars(name.trim().codePoints().findFirst().orElse('?'.code))).uppercase(Locale.ROOT)
            Text(initial, color = ink, style = MaterialTheme.typography.titleMedium)
        }
    }
}
