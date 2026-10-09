package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCaptureTest {
    @Test fun `all published TypeScript signatures verify and re-encode verbatim`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/recording-capture.json")).use { it.readBytes() }
        val cases = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("cases").jsonArray
        assertEquals(4, cases.size)
        for (value in cases) {
            val row = value.jsonObject
            fun text(key: String) = row.getValue(key).jsonPrimitive.content
            val encoded = text("encoded")
            val signed = requireNotNull(decodeRecordingCaptureOp(encoded))
            assertTrue(verifyRecordingCaptureNotice(text("roomId"), signed.notice, signed.sig, text("authority")))
            assertEquals(encoded, encodeRecordingCaptureOp(signed))
            for (changed in listOf(
                signed.notice.copy(id = "34".repeat(16)), signed.notice.copy(version = 99),
                signed.notice.copy(capture = if (signed.notice.capture == "audio") "gallery" else "audio"),
                signed.notice.copy(recorder = "cc".repeat(32)), signed.notice.copy(device = "dd".repeat(32)),
            )) assertFalse(verifyRecordingCaptureNotice(text("roomId"), changed, signed.sig, text("authority")))
            assertFalse(verifyRecordingCaptureNotice("cd".repeat(32), signed.notice, signed.sig, text("authority")))
            assertNull(decodeRecordingCaptureOp(encoded.replace("\"${signed.notice.capture}\"", "\"unknown\"")))
            assertNull(decodeRecordingCaptureOp(encoded.replace("\"version\":${signed.notice.version}", "\"version\":\"${signed.notice.version}\"")))
        }
    }
}
