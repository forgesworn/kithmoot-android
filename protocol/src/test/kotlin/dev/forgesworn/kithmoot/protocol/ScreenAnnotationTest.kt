package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ScreenAnnotation` validation, round-tripping and wrapping. The bounds
 * mirror the web client's `validScreenAnnotation` (`src/signal.ts`)
 * exactly; there are no annotation cases yet in
 * `vectors/kithmoot-vectors.json`, so these are written straight from the
 * documented bounds in docs/protocol.md, "Call signalling profile 1".
 */
class ScreenAnnotationTest {

    private fun point(x: Double, y: Double) = AnnotationPoint(x, y)
    private fun stroke(points: List<AnnotationPoint>, shareId: String = "screen-track", strokeId: String = "s1") =
        ScreenAnnotation(op = "stroke", shareId = shareId, strokeId = strokeId, points = points)
    private fun clear(shareId: String = "screen-track") =
        ScreenAnnotation(op = "clear", shareId = shareId, strokeId = "", points = null)

    // --- validity --------------------------------------------------------------

    @Test
    fun `a two-point stroke is valid`() {
        assertTrue(isValidScreenAnnotation(stroke(listOf(point(0.1, 0.2), point(0.8, 0.7)))))
    }

    @Test
    fun `a clear is valid`() {
        assertTrue(isValidScreenAnnotation(clear()))
    }

    @Test
    fun `a stroke with one point is invalid`() {
        assertFalse(isValidScreenAnnotation(stroke(listOf(point(0.1, 0.2)))))
    }

    @Test
    fun `a stroke with no points is invalid`() {
        assertFalse(isValidScreenAnnotation(ScreenAnnotation("stroke", "screen-track", "s1", points = null)))
    }

    @Test
    fun `a stroke with exactly the maximum points is valid`() {
        val points = List(MAX_ANNOTATION_POINTS) { point(0.5, 0.5) }
        assertTrue(isValidScreenAnnotation(stroke(points)))
    }

    @Test
    fun `a stroke with one point over the maximum is invalid`() {
        val points = List(MAX_ANNOTATION_POINTS + 1) { point(0.5, 0.5) }
        assertFalse(isValidScreenAnnotation(stroke(points)))
    }

    @Test
    fun `a coordinate outside zero to one is invalid`() {
        assertFalse(isValidScreenAnnotation(stroke(listOf(point(-0.1, 0.2), point(0.8, 0.7)))))
        assertFalse(isValidScreenAnnotation(stroke(listOf(point(0.1, 1.1), point(0.8, 0.7)))))
    }

    @Test
    fun `boundary coordinates of exactly zero and one are valid`() {
        assertTrue(isValidScreenAnnotation(stroke(listOf(point(0.0, 0.0), point(1.0, 1.0)))))
    }

    @Test
    fun `a non-finite coordinate is invalid`() {
        assertFalse(isValidScreenAnnotation(stroke(listOf(point(Double.NaN, 0.2), point(0.8, 0.7)))))
        assertFalse(isValidScreenAnnotation(stroke(listOf(point(Double.POSITIVE_INFINITY, 0.2), point(0.8, 0.7)))))
    }

    @Test
    fun `an empty shareId is invalid`() {
        assertFalse(isValidScreenAnnotation(stroke(listOf(point(0.1, 0.2), point(0.8, 0.7)), shareId = "")))
    }

    @Test
    fun `an empty strokeId on a stroke is invalid`() {
        assertFalse(isValidScreenAnnotation(stroke(listOf(point(0.1, 0.2), point(0.8, 0.7)), strokeId = "")))
    }

    @Test
    fun `a clear carrying points is invalid`() {
        assertFalse(isValidScreenAnnotation(ScreenAnnotation("clear", "screen-track", "", points = listOf(point(0.1, 0.2)))))
    }

    @Test
    fun `a clear carrying a non-empty strokeId is invalid`() {
        assertFalse(isValidScreenAnnotation(ScreenAnnotation("clear", "screen-track", "s1", points = null)))
    }

    @Test
    fun `an unknown op is invalid`() {
        assertFalse(isValidScreenAnnotation(ScreenAnnotation("erase", "screen-track", "s1", points = null)))
    }

    @Test
    fun `null is invalid`() {
        assertFalse(isValidScreenAnnotation(null))
    }

    // --- JSON round-trip ---------------------------------------------------------

    @Test
    fun `a stroke round-trips through JSON`() {
        val original = stroke(listOf(point(0.1, 0.2), point(0.8, 0.7)))
        val decoded = ScreenAnnotation.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun `a clear round-trips through JSON`() {
        val original = clear()
        val decoded = ScreenAnnotation.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun `malformed annotation JSON decodes to null rather than throwing`() {
        val malformed = Json.parseToJsonElement("""{"op":"stroke"}""").jsonObject
        assertNull(ScreenAnnotation.fromJson(malformed))
    }

    // --- inside a SignalBody -------------------------------------------------

    @Test
    fun `a SignalBody carrying an annotation round-trips`() {
        val body = SignalBody(
            type = "annotation",
            roomId = "d".repeat(64),
            annotation = stroke(listOf(point(0.1, 0.2), point(0.8, 0.7))),
        )
        val decoded = SignalBody.fromJson(body.toJson())
        assertEquals(body, decoded)
    }

    @Test
    fun `a SignalBody with no annotation decodes with a null annotation`() {
        val body = SignalBody(type = "offer", roomId = "d".repeat(64), sdp = "v=0")
        assertNull(SignalBody.fromJson(body.toJson()).annotation)
    }

    // --- wrapped end to end, exercising the existing signal decoding -------------

    @Test
    fun `an annotation signal wraps and unwraps like any other signal`() {
        val senderSecretKey = ByteArray(32) { (it + 1).toByte() }
        val recipientSecretKey = ByteArray(32) { (it + 40).toByte() }
        val recipient = Schnorr.publicKeyHex(recipientSecretKey)
        val roomId = "d".repeat(64)
        val body = SignalBody(
            type = "annotation",
            roomId = roomId,
            annotation = stroke(listOf(point(0.1, 0.2), point(0.8, 0.7))),
        )
        val wrap = wrapSignal(body = body, senderSecretKey = senderSecretKey, recipientPubkey = recipient, createdAt = 1_700_000_000L).wrap

        val unwrapped = unwrapSignal(wrap, recipientSecretKey, roomId, now = 1_700_000_000L)

        assertEquals(body, unwrapped?.body)
    }
}
