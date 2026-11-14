package dev.forgesworn.kithmoot.ui.qr

import dev.forgesworn.qr.EncodeOptions
import dev.forgesworn.qr.ErrorCorrection
import dev.forgesworn.qr.encode
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

    @Test fun readsASoftNoisyFrameAsACameraSeesAScreen() {
        // Blur and sensor noise at about 3.5 pixels per module, where the
        // interim ZXing reader missed codes it should have read.
        val sharp = render(bunker, width = 640, height = 480, rowStride = 640, side = 230)
        val frame = soften(sharp, 640, 480, seed = 7)
        assertEquals(bunker, QrFrameDecoder().decode(frame, 640, 480))
    }

    @Test fun logsTheSchemeOfAValueNeverTheValue() {
        assertEquals("bunker", schemeOf(bunker))
        assertEquals("https", schemeOf(roomLink))
        assertEquals("none", schemeOf("just some words"))
        assertEquals("none", schemeOf("a very long prefix before any colon: x"))
    }

    /** A 3x3 box blur plus plus-or-minus 12 grey levels of noise. */
    private fun soften(frame: ByteArray, width: Int, height: Int, seed: Int): ByteArray {
        val random = java.util.Random(seed.toLong())
        val out = ByteArray(frame.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy !in 0 until height || xx !in 0 until width) continue
                sum += frame[yy * width + xx].toInt() and 0xFF
                n++
            }
            val noisy = sum / n + random.nextInt(25) - 12
            out[y * width + x] = noisy.coerceIn(0, 255).toByte()
        }
        return out
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
        val qr = encode(text, EncodeOptions(ecc = ErrorCorrection.Q))
        val modules = qr.size + 8
        // The symbol's pixel at (x, y) inside a side-by-side square with a four-module quiet zone.
        val dark = { x: Int, y: Int ->
            val mx = x * modules / side - 4
            val my = y * modules / side - 4
            mx in 0 until qr.size && my in 0 until qr.size && qr.isDark(mx, my)
        }
        val ink = if (inverted) 0xE0.toByte() else 0x20.toByte()
        val light = if (inverted) 0x20.toByte() else 0xE0.toByte()
        val frame = ByteArray(rowStride * height) { light }
        val left = (width - side) / 2
        val top = (height - side) / 2
        for (y in 0 until side) for (x in 0 until side) {
            val on = if (rotate) dark(y, side - 1 - x) else dark(x, y)
            if (on) frame[(top + y) * rowStride + left + x] = ink
        }
        // Junk in the stride padding must not leak into the picture.
        for (y in 0 until height) for (x in width until rowStride) frame[y * rowStride + x] = 0
        return frame
    }
}
