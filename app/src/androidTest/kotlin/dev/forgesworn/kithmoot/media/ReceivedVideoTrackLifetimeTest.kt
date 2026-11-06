package dev.forgesworn.kithmoot.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

@RunWith(AndroidJUnit4::class)
class ReceivedVideoTrackLifetimeTest {
    @Test fun retainedCallbackVideoSurvivesOwnerDisposalAndLateViewCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(false)
        val callbackTrack = factory.createVideoTrack("owned-receiver-test", source)
        val tracks = ReceivedTracks<VideoTrack>(
            retain = { id, track -> RetainedVideoTrack.retain(track, id) },
            release = VideoTrack::dispose,
            sameSource = { held, incoming -> held is RetainedVideoTrack && held.refersTo(incoming) },
        )
        var callbackDisposed = false
        try {
            tracks[callbackTrack.id()] = callbackTrack
            val held = tracks.values.single()
            tracks[callbackTrack.id()] = callbackTrack
            assertSame(held, tracks.values.single())
            val frameArrived = CountDownLatch(1)
            val sink = VideoSink { frameArrived.countDown() }
            held.addSink(sink)
            callbackTrack.dispose(); callbackDisposed = true
            source.capturerObserver.onCapturerStarted(true)
            repeat(3) {
                val frame = VideoFrame(JavaI420Buffer.allocate(16, 16), 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
            }
            assertTrue("Owned native track must still deliver video", frameArrived.await(3, TimeUnit.SECONDS))
            tracks.close()
            // Models Compose removing a tile after receiver teardown. Its old
            // sink has already been removed and freed exactly once.
            held.removeSink(sink)
            held.addSink(sink)
            held.dispose()
        } finally {
            tracks.close()
            if (!callbackDisposed) callbackTrack.dispose()
            source.dispose()
            factory.dispose()
        }
    }
}
