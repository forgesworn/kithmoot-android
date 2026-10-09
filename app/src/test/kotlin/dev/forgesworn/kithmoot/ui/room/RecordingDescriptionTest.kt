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
            assertTrue(description.contains("npub"))
            assertTrue(requireNotNull(recordingLine(RecordingView.On("12".repeat(16), 1), description)).contains(description))
        }
    }
}
