package dev.forgesworn.kithmoot.ui.qr

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.qr.zxing.BinaryBitmap
import dev.forgesworn.kithmoot.qr.zxing.DecodeHintType
import dev.forgesworn.kithmoot.qr.zxing.qrcode.QRCodeReader
import dev.forgesworn.kithmoot.qr.zxing.RGBLuminanceSource
import dev.forgesworn.kithmoot.qr.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QrCodeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun displayedQrTracksTheCapabilityText() {
        val first = "https://example.test/j/#" + "a".repeat(600)
        val second = "https://example.test/p/#" + "b".repeat(600)
        val text = mutableStateOf(first)
        compose.setContent {
            QrCode(
                text = text.value,
                contentDescription = if (text.value == first) "First QR" else "Second QR",
                modifier = Modifier.size(300.dp),
            )
        }

        compose.waitUntil(10_000) { hasDescription("First QR") }
        val firstImage = compose.onNodeWithContentDescription("First QR").assertIsDisplayed().captureToImage()
        assertEquals(first, decode(firstImage.asAndroidBitmap()))

        compose.runOnUiThread { text.value = second }
        compose.waitUntil(10_000) { hasDescription("Second QR") }
        val secondImage = compose.onNodeWithContentDescription("Second QR").assertIsDisplayed().captureToImage()
        assertEquals(second, decode(secondImage.asAndroidBitmap()))
    }

    private fun hasDescription(value: String): Boolean =
        compose.onAllNodesWithContentDescription(value).fetchSemanticsNodes().isNotEmpty()

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val image = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader().decode(image, mapOf(DecodeHintType.TRY_HARDER to true)).text
    }
}
