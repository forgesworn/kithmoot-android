package dev.forgesworn.kithmoot.ui.qr

import dev.forgesworn.kithmoot.qr.zxing.BinaryBitmap
import dev.forgesworn.kithmoot.qr.zxing.DecodeHintType
import dev.forgesworn.kithmoot.qr.zxing.LuminanceSource
import dev.forgesworn.kithmoot.qr.zxing.PlanarYUVLuminanceSource
import dev.forgesworn.kithmoot.qr.zxing.ReaderException
import dev.forgesworn.kithmoot.qr.zxing.common.HybridBinarizer
import dev.forgesworn.kithmoot.qr.zxing.qrcode.QRCodeReader

/**
 * Decodes one QR code from a camera frame's luminance (Y) plane, wholly on
 * the device, with the vendored ZXing QR reader (docs/third-party/zxing.md).
 * Tries the frame as seen, then inverted, so light-on-dark codes read too.
 * Not thread-safe: use one instance per analysis thread.
 */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * [luma] holds [height] rows of [rowStride] bytes, the first [width] of
     * each being pixels. Returns the decoded text, or null if none was found.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        require(width > 0 && height > 0 && rowStride >= width) { "Bad frame geometry" }
        require(luma.size >= rowStride * (height - 1) + width) { "Frame is shorter than its geometry" }
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return attempt(source) ?: attempt(source.invert())
    }

    private fun attempt(source: LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
