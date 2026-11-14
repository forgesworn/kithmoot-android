package dev.forgesworn.kithmoot.ui.qr

import dev.forgesworn.qr.Inversion
import dev.forgesworn.qr.decode

/**
 * Decodes one QR code from a camera frame's luminance (Y) plane, wholly on
 * the device, with the vendored ForgeSworn QR library
 * (docs/third-party/forgesworn-qr.md). Light-on-dark codes read too.
 */
internal class QrFrameDecoder {
    /**
     * [luma] holds [height] rows of [rowStride] bytes, the first [width] of
     * each being pixels. Returns the decoded text, or null if none was found.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        require(width > 0 && height > 0 && rowStride >= width) { "Bad frame geometry" }
        require(luma.size >= rowStride * (height - 1) + width) { "Frame is shorter than its geometry" }
        return decode(luma, width, height, rowStride, Inversion.BOTH)?.text
    }
}
