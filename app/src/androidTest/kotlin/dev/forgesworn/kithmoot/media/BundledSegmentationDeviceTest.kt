package dev.forgesworn.kithmoot.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.datatransport.cct.CCTDestination
import com.google.android.datatransport.runtime.TransportRuntime
import com.google.mediapipe.proto.MediaPipeLoggingProto
import com.google.mediapipe.tasks.core.logging.RemoteLoggingClient
import dev.forgesworn.kithmoot.media.effects.MediaPipeSegmenter
import dev.forgesworn.kithmoot.media.effects.BackgroundChoice
import dev.forgesworn.kithmoot.media.effects.FrameCompositor
import dev.forgesworn.kithmoot.media.effects.PersonSegmenter
import dev.forgesworn.kithmoot.media.effects.SeaScene
import dev.forgesworn.kithmoot.media.effects.SegmentationMask
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/** Exercises the real bundled model and logger in the minified APK. Synthetic
 * input checks native integration and ownership; it does not establish cut-out quality. */
class BundledSegmentationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledModelMatchesItsReviewedDigest() {
        val bytes = context.assets.open(MediaPipeSegmenter.MODEL_ASSET).use { it.readBytes() }
        assertEquals(249_537, bytes.size)
        assertEquals("191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }

    @Test fun aKnownPortraitKeepsThePersonAndRejectsTheBackground() {
        val photo = requireNotNull(InstrumentationRegistry.getInstrumentation().context.assets
            .open("segmentation/astronaut.png").use { BitmapFactory.decodeStream(it) })
        try {
            for (side in listOf(photo.width, dev.forgesworn.kithmoot.media.effects.MAX_WORKING_WIDTH)) {
                val image = Bitmap.createScaledBitmap(photo, side, side, true)
                try {
                    MediaPipeSegmenter.open(context).use { segmenter ->
                        val mask = segmenter.segment(image, 1L)!!
                        assertEquals(image.width, mask.width)
                        assertEquals(image.height, mask.height)
                        fun mean(left: Int, top: Int, right: Int, bottom: Int): Double {
                            val x0 = left * mask.width / 256; val x1 = right * mask.width / 256
                            val y0 = top * mask.height / 256; val y1 = bottom * mask.height / 256
                            var sum = 0.0
                            for (y in y0 until y1) for (x in x0 until x1) sum += mask.data[y * mask.width + x]
                            return sum / ((x1 - x0) * (y1 - y0))
                        }
                        val person = mean(90, 35, 130, 85)
                        val background = mean(220, 10, 250, 35)
                        assertTrue("the astronaut must remain visible at $side: confidence=$person", person > 0.75)
                        assertTrue("the room must be removed at $side: confidence=$background", background < 0.25)
                    }
                } finally {
                    if (image !== photo) image.recycle()
                }
            }
        } finally { photo.recycle() }
    }

    @Test fun realInferenceCanReuseTheInputBitmapAndRepeatedTimestampsWithoutNetwork() {
        val before = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(before).detectNetwork().penaltyDeathOnNetwork().build())
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            MediaPipeSegmenter.open(context).use { segmenter ->
                for (stamp in listOf(1L, 1L, 0L, 2L)) {
                    val mask = segmenter.segment(bitmap, stamp)
                    assertNotNull("the bundled model must return a foreground mask", mask)
                    assertEquals(256, mask!!.width)
                    assertEquals(256, mask.height)
                    assertEquals(256 * 256, mask.data.size)
                    assertTrue(mask.data.all { it.isFinite() && it in 0f..1f })
                    assertFalse("MediaPipe must not recycle the compositor's bitmap", bitmap.isRecycled)
                    assertEquals(Color.GREEN, bitmap.getPixel(0, 0))
                }
            }
        } finally {
            bitmap.recycle()
            StrictMode.setThreadPolicy(before)
        }
    }

    @Test fun mediaPipeLoggerResolvesOnlyToOurNoOpTransport() {
        val client = RemoteLoggingClient(context)
        val field = RemoteLoggingClient::class.java.getDeclaredField("transport").apply { isAccessible = true }
        assertEquals("com.google.android.datatransport.NoOpTransport", field.get(client).javaClass.name)
        assertEquals("com.google.android.datatransport.NoOpTransportFactory",
            TransportRuntime.getInstance().newFactory(CCTDestination.INSTANCE).javaClass.name)
        client.logEvent(MediaPipeLoggingProto.MediaPipeLogExtension.getDefaultInstance())
        for (name in listOf(
            "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
        )) {
            try { Class.forName(name); fail("real telemetry runtime must be absent: $name") }
            catch (_: ClassNotFoundException) { /* expected */ }
        }
    }

    @Test fun invalidMasksNeverExposeTheInputCamera() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        for (mask in listOf(
            SegmentationMask(floatArrayOf(Float.NaN), 1, 1),
            SegmentationMask(floatArrayOf(Float.POSITIVE_INFINITY), 1, 1),
            SegmentationMask(floatArrayOf(1f), 2, 2),
            SegmentationMask(floatArrayOf(1f), -1, -1),
        )) {
            val compositor = FrameCompositor(context, openSegmenter = { object : PersonSegmenter {
                override fun segment(bitmap: Bitmap, timestampMs: Long) = mask
                override fun close() = Unit
            } })
            val buffer = JavaI420Buffer.allocate(64, 64)
            for (i in 0 until buffer.dataY.capacity()) buffer.dataY.put(i, 150.toByte())
            for (i in 0 until buffer.dataU.capacity()) buffer.dataU.put(i, 44.toByte())
            for (i in 0 until buffer.dataV.capacity()) buffer.dataV.put(i, 21.toByte())
            val input = VideoFrame(buffer, 0, 1L)
            try {
                val output = compositor.compose(input, BackgroundChoice(SeaScene.PLAIN), false)
                assertNotNull(output)
                try {
                    val yuv = output!!.buffer.toI420()!!
                    try {
                        for (y in 0 until yuv.height) for (x in 0 until yuv.width) {
                            assertTrue("invalid masks must keep the dark cover", (yuv.dataY.get(y * yuv.strideY + x).toInt() and 255) < 50)
                        }
                    } finally { yuv.release() }
                } finally { output!!.release() }
            } finally { input.release(); compositor.close() }
        }
    }

    @Test fun failedModelStartupIsAttemptedOnceUntilTheSessionResets() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        var attempts = 0
        val compositor = FrameCompositor(context, openSegmenter = {
            attempts++
            throw IllegalStateException("synthetic model startup failure")
        })
        val buffer = JavaI420Buffer.allocate(64, 64)
        buffer.dataY.put(ByteArray(buffer.dataY.capacity()) { 150.toByte() })
        buffer.dataU.put(ByteArray(buffer.dataU.capacity()) { 44.toByte() })
        buffer.dataV.put(ByteArray(buffer.dataV.capacity()) { 21.toByte() })
        try {
            repeat(60) { index ->
                val input = VideoFrame(buffer, 0, index * 1_000_000L)
                val output = requireNotNull(compositor.compose(input, BackgroundChoice(SeaScene.PLAIN), false))
                try {
                    val yuv = requireNotNull(output.buffer.toI420())
                    try {
                        for (y in 0 until yuv.height) for (x in 0 until yuv.width) {
                            assertTrue("Startup failure must keep the dark cover",
                                (yuv.dataY.get(y * yuv.strideY + x).toInt() and 255) < 50)
                        }
                    } finally { yuv.release() }
                } finally { output.release() }
            }
            assertEquals("Do not rebuild a failed graph for each frame", 1, attempts)
            compositor.pause()
            val output = compositor.compose(VideoFrame(buffer, 0, 61_000_000L), BackgroundChoice(SeaScene.PLAIN), false)
            output?.release()
            assertNotNull("A reset must retain safe output", output)
            assertEquals("A new session may try the model again", 2, attempts)
        } finally { compositor.close(); buffer.release() }
    }
}
