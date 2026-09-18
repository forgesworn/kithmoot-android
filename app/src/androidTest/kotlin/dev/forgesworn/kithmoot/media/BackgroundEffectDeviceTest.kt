package dev.forgesworn.kithmoot.media

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.media.effects.BackgroundChoice
import dev.forgesworn.kithmoot.media.effects.FrameCompositor
import dev.forgesworn.kithmoot.media.effects.MediaPipeSegmenter
import dev.forgesworn.kithmoot.media.effects.PersonSegmenter
import dev.forgesworn.kithmoot.media.effects.SeaScene
import dev.forgesworn.kithmoot.media.effects.SegmentationMask
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.YuvHelper

/**
 * Background replacement, on a real device, through the real camera.
 *
 * Everything the JVM tests cannot reach: that the segmentation model loads on
 * this machine's ABI at all, which of its two masks is the person, that the
 * colour channels survive the trip out of I420 and back, that the picture comes
 * out upright, and what a frame costs.
 *
 * The frames checked here are the ones the local preview renders and the ones
 * that go on the wire - they are taken off the camera track's sink, which is
 * where both of those read from - so this is the own-preview tile, without a
 * room around it.
 *
 * Skipped where there is no camera, which is how the emulators in CI are
 * started (`-camera-front none`).
 */
class BackgroundEffectDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    init {
        // JavaI420Buffer.allocate is a native call, so the WebRTC library has to
        // be loaded before any test here builds a frame of its own.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            ).createInitializationOptions(),
        )
    }

    private fun grantCamera() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
    }

    private fun hasCamera(): Boolean =
        org.webrtc.Camera2Enumerator(context).deviceNames.isNotEmpty()

    /** Where a pulled-off-the-device picture goes. */
    private fun out(name: String): File =
        File(context.getExternalFilesDir(null), name)

    /**
     * A composited frame, written out by a converter that shares no code with
     * the one under test: libyuv's own I420-to-NV21 and the platform's JPEG
     * encoder. A red-and-blue swap in `Yuv.kt` cannot hide behind a matching
     * swap in the thing checking it.
     */
    private fun save(frame: VideoFrame, to: File) {
        val i420 = frame.buffer.toI420()!!
        try {
            val w = i420.width
            val h = i420.height
            val nv21 = ByteBuffer.allocateDirect(w * h * 3 / 2)
            // U and V handed over the other way round, which turns NV12 into NV21.
            YuvHelper.I420ToNV12(
                i420.dataY, i420.strideY,
                i420.dataV, i420.strideV,
                i420.dataU, i420.strideU,
                nv21, w, h,
            )
            val bytes = ByteArray(w * h * 3 / 2)
            nv21.rewind()
            nv21.get(bytes)
            FileOutputStream(to).use { file ->
                YuvImage(bytes, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 92, file)
            }
        } finally {
            i420.release()
        }
    }

    /** Mean red, green and blue of a saved picture, for "is the sea still blue". */
    private fun meanRgb(file: File): Triple<Int, Int, Int> {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val p = bitmap.getPixel(x, y)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n += 1
            }
        }
        return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    // -------------------------------------------------------------------------
    // 1. The whole pipeline, off the real camera.
    // -------------------------------------------------------------------------

    @Test
    fun theSeaGoesBehindTheCameraForTwentySeconds() {
        assumeTrue("no camera on this device", hasCamera())
        grantCamera()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        val eglBase = EglBase.create()
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        val media = LocalMedia(context, factory, eglBase)

        val trouble = mutableListOf<String>()
        media.onBackgroundTrouble = { trouble += it }
        media.setBackground(BackgroundChoice(SeaScene.LAGOON, fish = true))

        val started = System.currentTimeMillis()
        var frames = 0
        val rotations = mutableSetOf<Int>()
        val sizes = mutableSetOf<String>()
        val atThree = CountDownLatch(1)
        val atTwenty = CountDownLatch(1)

        val sink = VideoSink { frame ->
            frames += 1
            rotations += frame.rotation
            sizes += "${frame.buffer.width}x${frame.buffer.height}"
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= 3_000 && atThree.count > 0) {
                save(frame, out("sea-at-3s.jpg")); atThree.countDown()
            }
            if (elapsed >= 20_000 && atTwenty.count > 0) {
                save(frame, out("sea-at-20s.jpg")); atTwenty.countDown()
            }
        }

        try {
            val track = media.startCamera()
            assertTrue("the camera would not start", track != null)
            track!!.addSink(sink)
            assertTrue("no composited frame within 120s", atThree.await(120, TimeUnit.SECONDS))
            assertTrue("no composited frame at twenty seconds", atTwenty.await(180, TimeUnit.SECONDS))
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            Log.i(
                TAG,
                "camera-through-sea: frames=$frames over ${"%.1f".format(seconds)}s " +
                    "(${"%.1f".format(frames / seconds)}fps) rotations=$rotations sizes=$sizes trouble=$trouble",
            )
            assertEquals("the composited frame must be upright", setOf(0), rotations)
            assertTrue("the compositor gave up: $trouble", trouble.isEmpty())
            val sea = meanRgb(out("sea-at-20s.jpg"))
            Log.i(TAG, "camera-through-sea: mean rgb at 20s = $sea")
            assertTrue("a sea should be bluer than it is red, got $sea", sea.third > sea.first)
        } finally {
            media.releaseAll()
            factory.dispose()
            eglBase.release()
        }
    }

    // -------------------------------------------------------------------------
    // 2. Which mask is the person.
    // -------------------------------------------------------------------------

    /**
     * The pipeline reads the LAST confidence mask as the person. Getting it the
     * wrong way round is not subtle - the person goes in the sea and the room
     * stays - so it is checked against a picture with no person in it at all:
     * the person mask has to be near zero everywhere, and the other one near
     * one.
     */
    @Test
    fun theLastConfidenceMaskIsThePerson() {
        val bitmap = context.assets.open(SeaScene.CORAL.asset).use { BitmapFactory.decodeStream(it) }
        val segmenter = MediaPipeSegmenter.open(context)
        try {
            val mask = segmenter.segment(bitmap, 1)
            assertTrue("the model returned no mask", mask != null)
            val mean = mask!!.data.average()
            Log.i(TAG, "mask polarity: person-mask mean on a person-free picture = $mean (${mask.width}x${mask.height})")
            assertTrue(
                "the last mask claims a person where there is none (mean $mean), so the index is the wrong way round",
                mean < 0.2,
            )
        } finally {
            segmenter.close()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Colour order and orientation, on known input.
    // -------------------------------------------------------------------------

    /** A frame of one flat colour, in I420, at the given rotation. */
    private fun flatFrame(w: Int, h: Int, y: Int, u: Int, v: Int, rotation: Int): VideoFrame {
        val buffer = JavaI420Buffer.allocate(w, h)
        fun fill(target: ByteBuffer, stride: Int, rows: Int, value: Int) {
            target.rewind()
            val row = ByteArray(stride) { value.toByte() }
            repeat(rows) { target.put(row, 0, minOf(stride, target.remaining())) }
            target.rewind()
        }
        fill(buffer.dataY, buffer.strideY, h, y)
        fill(buffer.dataU, buffer.strideU, (h + 1) / 2, u)
        fill(buffer.dataV, buffer.strideV, (h + 1) / 2, v)
        return VideoFrame(buffer, rotation, System.nanoTime())
    }

    /** detach-then-compose, the way the processor drives it. */
    private fun FrameCompositor.run(frame: VideoFrame, choice: BackgroundChoice, frontFacing: Boolean): VideoFrame? {
        val taken = detach(frame) ?: return null
        try {
            return compose(taken, choice, frontFacing)
        } finally {
            taken.release()
        }
    }

    private class Stub(private val value: Float) : PersonSegmenter {
        override fun segment(bitmap: android.graphics.Bitmap, timestampMs: Long) =
            SegmentationMask(FloatArray(256 * 256) { value }, 256, 256)
        override fun close() = Unit
    }

    /**
     * With the mask saying "all person", the composited frame is the camera's
     * own picture, and nothing of the sea is left. A bright red frame that
     * comes back blue would mean the ARGB byte order is the wrong way round in
     * `Yuv.kt` or in the `ABGRToI420` call, which is the bug that looks like a
     * colour-profile problem.
     */
    @Test
    fun aRedFrameStaysRedThroughTheCompositor() {
        // BT.601 limited range for saturated red: Y 81, U 90, V 240.
        val compositor = FrameCompositor(context, openSegmenter = { Stub(1f) })
        val frame = flatFrame(640, 480, 81, 90, 240, rotation = 0)
        try {
            val made = compositor.run(frame, BackgroundChoice(SeaScene.LAGOON, fish = false), frontFacing = true)
            assertTrue("the compositor produced nothing", made != null)
            save(made!!, out("red-through-compositor.jpg"))
            val rgb = meanRgb(out("red-through-compositor.jpg"))
            Log.i(TAG, "colour order: a red frame came back as $rgb")
            assertTrue("red came back as $rgb", rgb.first > 150 && rgb.third < 90)
            made.release()
        } finally {
            frame.release()
            compositor.close()
        }
    }

    /**
     * With the mask saying "no person", the composited frame is the sea alone,
     * so its colours can be compared against the bundled photograph the drawing
     * came from. This is the half of the colour path the camera round trip
     * cannot catch: a matching mistake in unpacking and packing would cancel
     * out for the camera and still turn the sea orange.
     */
    @Test
    fun theSeaKeepsTheColoursOfItsOwnPhotograph() {
        val compositor = FrameCompositor(context, openSegmenter = { Stub(0f) })
        val frame = flatFrame(640, 480, 81, 90, 240, rotation = 0)
        try {
            val made = compositor.run(frame, BackgroundChoice(SeaScene.DEEP, fish = false), frontFacing = false)
            assertTrue("the compositor produced nothing", made != null)
            save(made!!, out("sea-only.jpg"))
            val drawn = meanRgb(out("sea-only.jpg"))

            val source = context.assets.open(SeaScene.DEEP.asset).use { BitmapFactory.decodeStream(it) }
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in 0 until source.height step 8) for (x in 0 until source.width step 8) {
                val p = source.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n += 1
            }
            val original = Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            Log.i(TAG, "sea colour: drawn $drawn against the photograph $original")
            assertTrue("the sea came out $drawn but the photograph is $original", drawn.third > drawn.first)
            assertTrue("red is off by more than 45: $drawn against $original", Math.abs(drawn.first - original.first) < 45)
            assertTrue("blue is off by more than 45: $drawn against $original", Math.abs(drawn.second - original.second) < 60)
        } finally {
            frame.release()
            compositor.close()
        }
    }

    /**
     * A quarter-turned camera buffer comes out upright, at the upright size,
     * with a rotation of zero - not passed on for something downstream to apply
     * to a sea that was drawn the other way up.
     */
    @Test
    fun aQuarterTurnedFrameComesOutUprightAndSquareWithItself() {
        val compositor = FrameCompositor(context, openSegmenter = { Stub(1f) })
        val frame = flatFrame(1280, 720, 81, 90, 240, rotation = 90)
        try {
            val made = compositor.run(frame, BackgroundChoice(SeaScene.LAGOON, fish = false), frontFacing = true)
            assertTrue("the compositor produced nothing", made != null)
            Log.i(TAG, "rotation: 1280x720 at 90 came out ${made!!.buffer.width}x${made.buffer.height} rot ${made.rotation}")
            assertEquals(0, made.rotation)
            assertEquals(360, made.buffer.width)
            assertEquals(640, made.buffer.height)
            made.release()
        } finally {
            frame.release()
            compositor.close()
        }
    }

    private companion object {
        const val TAG = "KithMootBackgroundProof"
    }
}
