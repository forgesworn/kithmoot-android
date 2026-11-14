package dev.forgesworn.kithmoot.ui.qr

import dev.forgesworn.kithmoot.qr.zxing.BarcodeFormat
import dev.forgesworn.kithmoot.qr.zxing.EncodeHintType
import dev.forgesworn.kithmoot.qr.zxing.common.BitMatrix
import dev.forgesworn.kithmoot.qr.zxing.qrcode.QRCodeWriter
import dev.forgesworn.kithmoot.qr.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFrameDecoderTest {
    // Throwaway payloads only: none of these is a real room or signer.
    private val roomLink = "https://kithmoot.app/j/#" + "x".repeat(180)
    private val bunker = "bunker://" + "a".repeat(64) + "?relay=wss%3A%2F%2Frelay.example&secret=test"

    @Test fun readsALinkFromAPaddedCameraRow() {
        val frame = render(roomLink, width = 640, height = 480, rowStride = 704)
        assertEquals(roomLink, QrFrameDecoder().decode(frame, 640, 480, rowStride = 704))
    }

    @Test fun readsALongLinkSmallInTheFrame() {
        // A 600-character link at about three pixels per module, as a
        // phone at arm's length sees it in a 1280x960 analysis frame.
        val longLink = "https://kithmoot.app/j/#" + "y".repeat(576)
        val frame = render(longLink, width = 1280, height = 960, rowStride = 1280, side = 340)
        assertEquals(longLink, QrFrameDecoder().decode(frame, 1280, 960))
    }

    @Test fun readsAnInvertedCode() {
        val frame = render(bunker, width = 640, height = 480, rowStride = 640, inverted = true)
        assertEquals(bunker, QrFrameDecoder().decode(frame, 640, 480))
    }

    @Test fun readsACodeTurnedOnItsSide() {
        val frame = render(bunker, width = 480, height = 640, rowStride = 480, rotate = true)
        assertEquals(bunker, QrFrameDecoder().decode(frame, 480, 640))
    }

    @Test fun readsNonAsciiText() {
        val text = "KithMoot café ☕ 会議"
        val frame = render(text, width = 640, height = 480, rowStride = 640)
        assertEquals(text, QrFrameDecoder().decode(frame, 640, 480))
    }

    @Test fun anEmptyFrameHasNoCode() {
        val frame = ByteArray(640 * 480) { 0x80.toByte() }
        assertNull(QrFrameDecoder().decode(frame, 640, 480))
    }

    @Test fun oneDecoderReadsFramesInTurn() {
        val decoder = QrFrameDecoder()
        assertNull(decoder.decode(ByteArray(640 * 480), 640, 480))
        assertEquals(roomLink, decoder.decode(render(roomLink, 640, 480, 640), 640, 480))
        assertEquals(bunker, decoder.decode(render(bunker, 640, 480, 640), 640, 480))
    }

    private fun render(
        text: String,
        width: Int,
        height: Int,
        rowStride: Int,
        inverted: Boolean = false,
        rotate: Boolean = false,
        side: Int = minOf(width, height) * 3 / 4,
    ): ByteArray {
        val matrix: BitMatrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            side,
            side,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            ),
        )
        val dark = if (inverted) 0xE0.toByte() else 0x20.toByte()
        val light = if (inverted) 0x20.toByte() else 0xE0.toByte()
        val frame = ByteArray(rowStride * height) { light }
        val left = (width - side) / 2
        val top = (height - side) / 2
        for (y in 0 until side) for (x in 0 until side) {
            val on = if (rotate) matrix[y, side - 1 - x] else matrix[x, y]
            if (on) frame[(top + y) * rowStride + left + x] = dark
        }
        // Junk in the stride padding must not leak into the picture.
        for (y in 0 until height) for (x in width until rowStride) frame[y * rowStride + x] = 0
        return frame
    }
}
