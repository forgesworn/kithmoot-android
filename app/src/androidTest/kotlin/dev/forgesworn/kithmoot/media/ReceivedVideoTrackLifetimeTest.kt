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
        var localTrack: RetainedVideoTrack? = null
        val lateViewCleanup = mutableListOf<Pair<VideoTrack, VideoSink>>()
        try {
            tracks[callbackTrack.id()] = callbackTrack
            val held = tracks.values.single()
            tracks[callbackTrack.id()] = callbackTrack
            assertSame(held, tracks.values.single())
            val frameArrived = CountDownLatch(1)
            val sink = VideoSink { frameArrived.countDown() }
            held.addSink(sink)
            lateViewCleanup += held to sink
            callbackTrack.dispose(); callbackDisposed = true
            source.capturerObserver.onCapturerStarted(true)
            repeat(3) {
                val frame = VideoFrame(JavaI420Buffer.allocate(16, 16), 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
            }
            assertTrue("Owned native track must still deliver video", frameArrived.await(3, TimeUnit.SECONDS))
            tracks.close()

            val local = RetainedVideoTrack.adopt(factory.createVideoTrack("owned-local-test", source))
            localTrack = local
            val localFrame = CountDownLatch(1)
            val localSink = VideoSink { localFrame.countDown() }
            local.addSink(localSink)
            lateViewCleanup += local to localSink
            repeat(3) {
                val frame = VideoFrame(JavaI420Buffer.allocate(16, 16), 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
            }
            assertTrue("Adopted local track must deliver video", localFrame.await(3, TimeUnit.SECONDS))
        } finally {
            tracks.close()
            localTrack?.dispose()
            if (!callbackDisposed) callbackTrack.dispose()
            source.dispose()
            factory.dispose()
        }
        // Compose can forget both local and received views after the entire
        // engine, including its factory threads, has gone. No JNI is safe here.
        for ((track, sink) in lateViewCleanup) {
            track.removeSink(sink)
            track.addSink(sink)
            track.dispose()
        }
    }
}
