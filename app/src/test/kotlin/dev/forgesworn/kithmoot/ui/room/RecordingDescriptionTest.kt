package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.RecordingCaptureNotice
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.ui.RoomState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingDescriptionTest {
    @Test fun `missing details retain a warning about audio and video`() {
        val line = requireNotNull(recordingLine(RecordingView.On("12".repeat(16), 1)))
        assertTrue(line.contains("Audio and video you share may be included."))
        assertNull(recordingLine(RecordingView.Off))
    }

    @Test fun `each supported capture is described with recorder attribution`() {
        val words = mapOf("audio" to "call audio", "gallery" to "gallery video", "speaker" to "speaker video", "screen-camera" to "screen share with camera")
        for ((mode, expected) in words) {
            val state = RoomState(recordingCapture = RecordingCaptureNotice("12".repeat(16), 1, mode, "aa".repeat(32), "bb".repeat(32)))
            val description = requireNotNull(recordingCaptureDescription(state))
            assertTrue(description.contains(expected))
            // Named, never keyed (design-calm-shell check 13): with no name it is "Somebody".
            assertTrue(description.startsWith("Somebody is capturing"))
            assertTrue(!description.contains("npub"))
            assertTrue(requireNotNull(recordingLine(RecordingView.On("12".repeat(16), 1), description)).contains(description))
        }
    }
    @Test fun `multiple recorder descriptions do not imply only the first device is capturing`() {
        val capture = RecordingCaptureNotice("12".repeat(16), 1, "gallery", "aa".repeat(32), "bb".repeat(32))
        val view = RecordingView.On(capture.id, 1)
        val warnings = listOf(
            dev.forgesworn.kithmoot.session.RecordingWarning(view, capture, "ab".repeat(16), capture.recorder, capture.device),
            dev.forgesworn.kithmoot.session.RecordingWarning(view, capture.copy(device = "cc".repeat(32)), "cd".repeat(16), capture.recorder, "cc".repeat(32)))
        val state = RoomState(recording = view, recordingCapture = capture, recordingWarnings = warnings)
        assertTrue(requireNotNull(recordingCaptureDescription(state)).contains("1 other recording device"))
        assertTrue(requireNotNull(recordingLine(view, inRoom = true)).startsWith("A call in this room"))
    }

    @Test fun `all available capture details appear without repeating an other device count`() {
        val audio = RecordingCaptureNotice("12".repeat(16), 1, "audio", "aa".repeat(32), "bb".repeat(32))
        val gallery = RecordingCaptureNotice("34".repeat(16), 1, "gallery", "cc".repeat(32), "dd".repeat(32))
        val warnings = listOf(audio, gallery).map {
            dev.forgesworn.kithmoot.session.RecordingWarning(RecordingView.On(it.id, 1), it)
        }
        val state = RoomState(recordingCaptures = listOf(audio, gallery), recordingWarnings = warnings)
        val description = requireNotNull(recordingCaptureDescription(state))
        assertTrue(description.contains("capturing call audio"))
        assertTrue(description.contains("capturing gallery video and call audio"))
        assertTrue(!description.contains("other recording device"))
    }

}
