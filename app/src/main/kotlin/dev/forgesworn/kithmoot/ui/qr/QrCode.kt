package dev.forgesworn.kithmoot.ui.qr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.forgesworn.qr.EncodeOptions
import dev.forgesworn.qr.ErrorCorrection
import dev.forgesworn.qr.encode

private const val QR_SIZE_PX = 768
private const val QUIET_ZONE = 4
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** Renders an already-visible capability as a QR without sending it anywhere. */
@Composable
fun QrCode(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { encodeQrBitmap(text, QR_SIZE_PX) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.widthIn(max = 320.dp).aspectRatio(1f),
        )
    }
}

private fun encodeQrBitmap(text: String, sizePx: Int): Bitmap? = runCatching {
    require(text.isNotBlank()) { "QR text is required" }
    require(sizePx in 64..2048) { "QR size is out of bounds" }
    val qr = encode(text, EncodeOptions(ecc = ErrorCorrection.M))
    // The standard four-module quiet zone, and whole-pixel module edges so
    // no module is drawn as a blurred half-pixel.
    val side = qr.size + 2 * QUIET_ZONE
    require(sizePx >= side) { "QR size is too small for this code" }
    val edge = { module: Int -> module * sizePx / side }
    val pixels = IntArray(sizePx * sizePx) { WHITE }
    for (my in 0 until qr.size) for (mx in 0 until qr.size) {
        if (!qr.isDark(mx, my)) continue
        for (y in edge(my + QUIET_ZONE) until edge(my + QUIET_ZONE + 1)) {
            for (x in edge(mx + QUIET_ZONE) until edge(mx + QUIET_ZONE + 1)) pixels[y * sizePx + x] = BLACK
        }
    }
    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).also { bitmap ->
        bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}.getOrNull()
