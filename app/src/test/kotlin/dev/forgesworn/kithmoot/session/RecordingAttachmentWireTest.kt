package dev.forgesworn.kithmoot.session

import kotlin.test.*

class RecordingAttachmentWireTest {
    @Test fun `PWA recording codec parameters survive the received chat descriptor`() {
        for (type in listOf("audio/webm;codecs=opus", "audio/ogg;codecs=opus",
            "video/webm;codecs=vp8,opus", "video/webm;codecs=vp9,opus",
            "video/mp4;codecs=avc1.42e01e,mp4a.40.2")) {
            val sent = ChatAttachment("https://private.example/${"ab".repeat(32)}", "ab".repeat(32),
                "cd".repeat(32), "synthetic recording", type, 65592)
            val received = assertNotNull(parseAttachment(sent.toJson()))
            assertEquals(sent.toJson(), received.toJson())
            assertEquals(type.substringBefore(';'), recordingPlaybackMime(received.type))
        }
    }

    @Test fun `unsupported or unsafe MIME parameters cannot select the recording player`() {
        for (type in listOf("image/png;codecs=opus", "video/webm;codecs=opus\r\nX-Header: value",
            "audio/ogg;codecs=\topus", "video/mp4;", "video/webm;codecs=" + "x".repeat(128))) {
            val received = assertNotNull(parseAttachment(ChatAttachment("https://private.example/file",
                "ab".repeat(32), "cd".repeat(32), type = type).toJson()))
            assertNull(received.type)
            assertNull(recordingPlaybackMime(received.type))
        }
    }
}
