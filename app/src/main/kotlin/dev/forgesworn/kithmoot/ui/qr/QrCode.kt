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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private const val QR_SIZE_PX = 768
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
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until sizePx) for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) BLACK else WHITE)
        }
    }
}.getOrNull()
