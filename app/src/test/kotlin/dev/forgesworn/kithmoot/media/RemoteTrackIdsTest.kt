package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteTrackIdsTest {
    @Test fun `binds camera and screen by negotiated section not receiver id or arrival order`() {
        val sdp = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=mid:camera
            a=sendrecv
            a=msid:- camera-new
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=mid:mic
            a=sendonly
            a=msid:stream microphone
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=mid:screen
            a=msid:stream screen-original
        """.trimIndent()
        assertEquals(mapOf("camera" to "camera-new", "mic" to "microphone", "screen" to "screen-original"), remoteTrackIds(sdp))
    }

    @Test fun `drops stopped rejected and receive-only sections`() {
        val sdp = """
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=mid:0
            a=inactive
            a=msid:- stopped
            m=video 0 UDP/TLS/RTP/SAVPF 96
            a=mid:1
            a=msid:- rejected
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=mid:2
            a=recvonly
            a=msid:- not-sending
        """.trimIndent()
        assertEquals(emptyMap(), remoteTrackIds(sdp))
    }
}
