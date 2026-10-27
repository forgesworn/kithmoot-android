package dev.forgesworn.kithmoot.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.media.effects.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.webrtc.*

/** Explicit emulator diagnostics. Rates describe this virtual camera, not a phone. */
class CameraLifecycleProfileTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun profileCaptureAndExerciseEffectLifecycle() {
        assumeTrue("Explicit emulator camera profile only",
            InstrumentationRegistry.getArguments().getString("cameraProfile") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        assertTrue("Configure an emulator camera", Camera2Enumerator(context).deviceNames.isNotEmpty())
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val media = LocalMedia(context, factory, egl)
        val frames = AtomicLong()
        val trouble = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val sink = VideoSink { frames.incrementAndGet() }
        var track: VideoTrack? = null
        val report = JSONObject().put("scope", "API ${android.os.Build.VERSION.SDK_INT} emulator camera; foreground activity; no remote peer")
        val phases = JSONArray()
        report.put("phases", phases)
        val output = File(context.getExternalFilesDir(null), "camera-lifecycle-profile.json")
        fun phase(name: String, seconds: Int = 6): Long {
            val before = frames.get()
            val start = SystemClock.elapsedRealtime()
            SystemClock.sleep(seconds * 1000L)
            val elapsed = SystemClock.elapsedRealtime() - start
            val count = frames.get() - before
            phases.put(JSONObject().put("name", name).put("frames", count).put("elapsedMs", elapsed)
                .put("fps", count * 1000.0 / elapsed))
            output.writeText(report.toString(2))
            return count
        }
        fun startCamera() {
            track = requireNotNull(media.startCamera())
            track!!.addSink(sink)
            val before = frames.get()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (frames.get() == before && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Camera must deliver its first frame", frames.get() > before)
        }
        try {
            media.onBackgroundTrouble = { trouble.add(it) }
            media.setBackground(BackgroundChoice())
            startCamera()
            SystemClock.sleep(2000)
            assertTrue("Raw camera continues", phase("raw") >= 5)
            media.setBackground(BackgroundChoice(SeaScene.LAGOON, fish = true))
            SystemClock.sleep(3000)
            assertTrue("Bundled segmentation and fish continue", phase("sea-and-fish") >= 5)
            for (cycle in 1..3) {
                media.setAppVisible(false)
                // Allow a sink callback that was already being delivered to return.
                SystemClock.sleep(500)
                assertEquals("Hidden effect must publish no camera frames", 0L, phase("hidden-$cycle", 2))
                media.setAppVisible(true)
                assertTrue("Effect resumes after visibility restoration", phase("resumed-$cycle") >= 5)
                media.switchCamera()
                assertTrue("Effect resumes after camera switch", phase("switched-$cycle") >= 5)
                track!!.removeSink(sink)
                media.stopCamera()
                assertEquals("Stopped camera must publish no frames", 0L, phase("stopped-$cycle", 1))
                startCamera()
                assertTrue("Chosen effect survives camera restart", phase("restarted-$cycle") >= 5)
            }
            assertTrue("No compositor failure notification: $trouble", trouble.isEmpty())
            media.setBackground(BackgroundChoice())
            assertTrue("Turning effects off restores raw capture", phase("raw-restored") >= 5)
            report.put("synthetic", profileComposition(context))
            report.put("passed", true)
        } finally {
            track?.removeSink(sink)
            media.releaseAll()
            factory.dispose()
            egl.release()
            output.writeText(report.toString(2))
        }
    }

    private fun profileComposition(context: android.content.Context): JSONObject {
        val portrait = requireNotNull(InstrumentationRegistry.getInstrumentation().context.assets
            .open("segmentation/astronaut.png").use { BitmapFactory.decodeStream(it) })
        val bitmap = Bitmap.createScaledBitmap(portrait, 640, 360, true)
        val packed = ByteBuffer.allocateDirect(640 * 360 * 4)
        bitmap.copyPixelsToBuffer(packed); packed.rewind()
        val buffer = JavaI420Buffer.allocate(640, 360)
        YuvHelper.ABGRToI420(packed, 640 * 4, buffer.dataY, buffer.strideY,
            buffer.dataU, buffer.strideU, buffer.dataV, buffer.strideV, 640, 360)
        val inference = ArrayList<Double>()
        val detach = ArrayList<Double>()
        val compose = ArrayList<Double>()
        val compositor = FrameCompositor(context, openSegmenter = {
            val model = MediaPipeSegmenter.open(it)
            object : PersonSegmenter {
                override fun segment(bitmap: Bitmap, timestampMs: Long): SegmentationMask? {
                    val began = System.nanoTime()
                    return model.segment(bitmap, timestampMs).also {
                        inference.add((System.nanoTime() - began) / 1_000_000.0)
                    }
                }
                override fun close() = model.close()
            }
        })
        try {
            repeat(45) {
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                val began = System.nanoTime()
                val detached = requireNotNull(compositor.detach(frame))
                detach.add((System.nanoTime() - began) / 1_000_000.0)
                try {
                    val composeBegan = System.nanoTime()
                    val made = requireNotNull(compositor.compose(detached,
                        BackgroundChoice(SeaScene.LAGOON, fish = true), true))
                    compose.add((System.nanoTime() - composeBegan) / 1_000_000.0)
                    made.release()
                } finally { detached.release() }
            }
            fun summary(values: List<Double>) = values.drop(15).sorted().let {
                JSONObject().put("count", it.size).put("meanMs", it.average())
                    .put("medianMs", it[it.size / 2]).put("p95Ms", it[(it.size * 0.95).toInt().coerceAtMost(it.lastIndex)])
            }
            return JSONObject().put("input", "640x360 bundled portrait in I420; excludes camera texture readback")
                .put("detach", summary(detach)).put("composeIncludingInference", summary(compose))
                .put("inference", summary(inference))
        } finally {
            compositor.close(); buffer.release()
            if (bitmap !== portrait) bitmap.recycle()
            portrait.recycle()
        }
    }
}
