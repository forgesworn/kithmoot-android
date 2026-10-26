package dev.forgesworn.kithmoot.media

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.media.effects.BackgroundChoice
import dev.forgesworn.kithmoot.media.effects.FrameCompositor
import dev.forgesworn.kithmoot.media.effects.PersonSegmenter
import dev.forgesworn.kithmoot.media.effects.ReefOverlay
import dev.forgesworn.kithmoot.media.effects.ReefShoal
import dev.forgesworn.kithmoot.media.effects.ReducedMotion
import dev.forgesworn.kithmoot.media.effects.SeaScene
import dev.forgesworn.kithmoot.media.effects.SegmentationMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/** Synthetic frames and real Canvas/WebRTC output. Comparing identical reefs
 * with and without spawning isolates fish pixels from light shafts and motes. */
class ReefBackdropDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    }

    private fun reef(spawn: Boolean) = ReefOverlay(ReefShoal(
        random = { 0.4f },
        firstSpawnMs = if (spawn) 0L else Long.MAX_VALUE,
        minGapMs = 1_000_000L,
        maxGapMs = 1_000_000L,
    ))

    private fun luma(frame: VideoFrame): ByteArray {
        val buffer = frame.buffer.toI420()!!
        return try {
            ByteArray(buffer.width * buffer.height) { index ->
                buffer.dataY.get(index / buffer.width * buffer.strideY + index % buffer.width)
            }
        } finally { buffer.release() }
    }

    private fun assertFishDrawn(openSegmenter: ((Context) -> PersonSegmenter)? = null) {
        var now = 0L
        val withFish = FrameCompositor(context, openSegmenter = openSegmenter, reef = reef(true), clockMs = { now })
        val withoutFish = FrameCompositor(context, openSegmenter = openSegmenter, reef = reef(false), clockMs = { now })
        val input = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, 1L)
        val choice = BackgroundChoice(SeaScene.LAGOON, fish = true)
        try {
            var swimming = byteArrayOf()
            var empty = byteArrayOf()
            for (step in 0..100) {
                now = step * 100L
                val a = withFish.compose(input, choice, false)!!
                val b = withoutFish.compose(input, choice, false)!!
                try {
                    if (step == 0) assertArrayEquals("fish starts outside the picture", luma(a), luma(b))
                    if (step == 100) { swimming = luma(a); empty = luma(b) }
                } finally { a.release(); b.release() }
            }
            assertTrue("a swimming fish must change visible pixels", swimming.indices.count { swimming[it] != empty[it] } > 20)
            withFish.pause()
            val resumed = withFish.compose(input, choice, false)!!
            try { assertArrayEquals("sprites reload after pause", swimming, luma(resumed)) } finally { resumed.release() }
        } finally { input.release(); withFish.close(); withoutFish.close() }
    }

    @Test fun fishSwimWithoutAnIntegratedSegmenter() = assertFishDrawn()

    @Test fun fishSwimWhenSegmentationReturnsNoMask() = assertFishDrawn {
        object : PersonSegmenter {
            override fun segment(bitmap: Bitmap, timestampMs: Long): SegmentationMask? = null
            override fun close() = Unit
        }
    }

    @Test fun fishSwimWhenTheModelCannotLoad() = assertFishDrawn { throw IllegalStateException("test missing model") }

    @Test fun fishStillSwimBehindASegmentedForeground() = assertFishDrawn {
        object : PersonSegmenter {
            override fun segment(bitmap: Bitmap, timestampMs: Long) =
                SegmentationMask(FloatArray(16 * 16) { if (it % 16 < 4) 1f else 0f }, 16, 16)
            override fun close() = Unit
        }
    }

    @Test fun wholePictureBackgroundsNeverReadCameraPixelsAndStayUpright() {
        val unreadable = object : VideoFrame.Buffer {
            override fun getWidth() = 320
            override fun getHeight() = 240
            override fun retain() = Unit
            override fun release() = Unit
            override fun toI420(): VideoFrame.I420Buffer = error("must not read camera pixels")
            override fun cropAndScale(x: Int, y: Int, cw: Int, ch: Int, sw: Int, sh: Int): VideoFrame.Buffer =
                error("must not read camera texture")
        }
        val input = VideoFrame(unreadable, 90, 1L)
        val compositor = FrameCompositor(context)
        try {
            for (scene in SeaScene.entries) {
                val choice = BackgroundChoice(scene, fish = false)
                val detached = compositor.detach(input, choice)!!
                val output = try { compositor.compose(detached, choice, true)!! } finally { detached.release() }
                try {
                    assertEquals(240, output.buffer.width)
                    assertEquals(320, output.buffer.height)
                    assertEquals(0, output.rotation)
                    assertTrue("opaque replacement must be published", luma(output).any { (it.toInt() and 255) > 16 })
                } finally { output.release() }
            }
        } finally { input.release(); compositor.close() }
    }

    @Test fun fishOffAndReducedMotionKeepTheBackdropStill() {
        var now = 0L
        val off = FrameCompositor(context, reef = reef(true), clockMs = { now })
        val reduced = FrameCompositor(context, reef = reef(true), clockMs = { now }, reducedMotion = { true })
        val input = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, 1L)
        try {
            var previous = byteArrayOf()
            for (step in 0..2) {
                now = step * 10_000L
                val a = off.compose(input, BackgroundChoice(SeaScene.LAGOON, fish = false), false)!!
                val b = reduced.compose(input, BackgroundChoice(SeaScene.LAGOON, fish = true), false)!!
                try {
                    val pixels = luma(a)
                    assertFalse(pixels.isEmpty())
                    assertArrayEquals("reduced motion removes the animation", pixels, luma(b))
                    if (step > 0) assertArrayEquals("fish off keeps a still scene", previous, pixels)
                    previous = pixels
                } finally { a.release(); b.release() }
            }
        } finally { input.release(); off.close(); reduced.close() }
    }

    @Test fun reducedMotionReadsTheSystemSettingBeforeCaching() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val resolver = context.contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, key)
        var now = 0L
        val motion = ReducedMotion(context, clockMs = { now })
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Global.putFloat(resolver, key, 0f))
            assertTrue("the first read must honour reduced motion", motion.on())
            assertTrue(Settings.Global.putFloat(resolver, key, 1f))
            now = 500L
            assertTrue("the cached answer lasts one second", motion.on())
            now = 1_000L
            assertFalse("later reads must notice a setting change", motion.on())
        } finally {
            try { Settings.Global.putString(resolver, key, original) } finally { automation.dropShellPermissionIdentity() }
        }
    }
}
