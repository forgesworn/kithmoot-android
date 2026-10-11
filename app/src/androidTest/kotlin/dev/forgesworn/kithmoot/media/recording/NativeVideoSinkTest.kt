package dev.forgesworn.kithmoot.media.recording

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*

/** The compositor needs independent sinks on live call tracks. This tests the
 * native source/sink contract without networking, codecs, UI tiles or a camera. */
class NativeVideoSinkTest {
    @Test fun a_sink_attached_after_capture_starts_and_after_a_sinkless_interval_receives_new_frames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(false)
        val track = factory.createVideoTrack("synthetic-late-sink", source)
        val count = AtomicInteger()
        val sink = VideoSink { count.incrementAndGet() }
        fun feed() = repeat(12) {
            val frame = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, System.nanoTime())
            try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
            SystemClock.sleep(100)
        }
        try {
            source.capturerObserver.onCapturerStarted(true)
            feed()
            track.addSink(sink)
            feed()
            assertTrue("A late source sink receives new frames", count.get() >= 3)
            track.removeSink(sink)
            val before = count.get()
            feed()
            assertEquals(before, count.get())
            track.addSink(sink)
            feed()
            assertTrue("A reattached sink receives new frames", count.get() >= before + 3)
        } finally {
            source.capturerObserver.onCapturerStopped()
            track.removeSink(sink); track.dispose(); source.dispose(); factory.dispose()
        }
    }

    @Test fun synthetic_source_delivers_frames_to_independent_video_sinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(false)
        val track = factory.createVideoTrack("synthetic-independent-sinks", source)
        val first = AtomicInteger()
        val second = AtomicInteger()
        val error = AtomicReference<String?>(null)
        val frames = CountDownLatch(6)
        val firstSink = VideoSink { frame ->
            if (frame.rotatedWidth != 320 || frame.rotatedHeight != 240) error.set("Unexpected frame dimensions")
            first.incrementAndGet(); frames.countDown()
        }
        val secondSink = VideoSink { second.incrementAndGet(); frames.countDown() }
        try {
            track.addSink(firstSink); track.addSink(secondSink)
            source.capturerObserver.onCapturerStarted(true)
            repeat(12) {
                val buffer = JavaI420Buffer.allocate(320, 240)
                for (i in 0 until buffer.dataY.capacity()) buffer.dataY.put(i, 160.toByte())
                for (i in 0 until buffer.dataU.capacity()) buffer.dataU.put(i, 90.toByte())
                for (i in 0 until buffer.dataV.capacity()) buffer.dataV.put(i, 130.toByte())
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                SystemClock.sleep(100)
            }
            assertTrue("Native independent sinks: first=${first.get()} second=${second.get()} source=${source.state()}", frames.await(5, TimeUnit.SECONDS))
            assertNull(error.get())
            assertTrue(first.get() >= 3)
            assertTrue(second.get() >= 3)
            // Removing a compositor sink must preserve the call's own sink.
            track.removeSink(firstSink)
            val heldFirst = first.get()
            val heldSecond = second.get()
            repeat(6) {
                val frame = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                SystemClock.sleep(100)
            }
            assertEquals(heldFirst, first.get())
            assertTrue("The other sink continues after removal", second.get() > heldSecond)
        } finally {
            source.capturerObserver.onCapturerStopped()
            track.removeSink(firstSink); track.removeSink(secondSink)
            track.dispose(); source.dispose(); factory.dispose()
        }
    }
}
