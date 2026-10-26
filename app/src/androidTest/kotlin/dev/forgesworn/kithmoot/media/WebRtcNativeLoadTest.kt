package dev.forgesworn.kithmoot.media

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

/** Run against the minified release too: debug keeps classes that R8 can strip.
 * Initialises JNI and creates native sources without capturing audio or video. */
class WebRtcNativeLoadTest {
    @Test fun nativeLibraryAndFactoryLoadWithoutCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        try {
            val audio = factory.createAudioSource(MediaConstraints())
            try {
                assertNotNull(audio)
                val video = factory.createVideoSource(false)
                try {
                    assertNotNull(video)
                } finally {
                    video.dispose()
                }
            } finally {
                audio.dispose()
            }
        } finally {
            factory.dispose()
        }
    }
}
