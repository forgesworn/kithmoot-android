package dev.forgesworn.kithmoot.media

import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.media.effects.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame

/** Real Canvas, vector, YUV conversion and missing-model fallback, with synthetic
 * frames only. No camera, real face, microphone or room data is needed. */
class FacePrivacyDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    }

    private fun green(rotation: Int = 0): VideoFrame {
        val buffer = JavaI420Buffer.allocate(320, 240)
        for (i in 0 until buffer.dataY.capacity()) buffer.dataY.put(i, 150.toByte())
        for (i in 0 until buffer.dataU.capacity()) buffer.dataU.put(i, 44.toByte())
        for (i in 0 until buffer.dataV.capacity()) buffer.dataV.put(i, 21.toByte())
        return VideoFrame(buffer, rotation, 1L)
    }

    private fun y(frame: VideoFrame, x: Int, y: Int): Int {
        val buffer = frame.buffer.toI420()!!
        return try { buffer.dataY.get(y * buffer.strideY + x).toInt() and 255 } finally { buffer.release() }
    }

    @Test fun avatarUsesNeitherDetectorNorSegmenterAndReplacesEveryInputPixel() {
        val compositor = FrameCompositor(context, openSegmenter = { error("avatar must not segment") },
            openFaceDetector = { error("avatar must not detect") })
        val input = green(90)
        try {
            val choice = BackgroundChoice(face = FacePrivacyMode.AVATAR)
            val detached = compositor.detach(input, choice)!!
            val output = try { compositor.compose(detached, choice, true)!! } finally { detached.release() }
            try {
                assertEquals(240, output.buffer.width)
                assertEquals(320, output.buffer.height)
                assertEquals(0, output.rotation)
                assertTrue(y(output, 0, 0) < 50)
                assertTrue(y(output, 239, 319) < 50)
                assertTrue(y(output, 120, 70) > 170)
            } finally { output.release() }
        } finally { input.release(); compositor.close() }
    }

    @Test fun everyDetectionIsCoveredAndLostDetectionReplacesTheWholePicture() {
        var faces = listOf(FaceBox(70f, 60f, 50f, 60f), FaceBox(230f, 100f, 30f, 40f))
        val compositor = FrameCompositor(context, openFaceDetector = { object : FaceLocator {
            override fun detect(bitmap: Bitmap, timestampMs: Long) = faces
            override fun close() {}
        } })
        val input = green()
        val choice = BackgroundChoice(face = FacePrivacyMode.MASK)
        try {
            val output = compositor.compose(input, choice, true)!!
            try {
                assertTrue(y(output, 50, 32) < 60) // expanded first box's opaque corner
                assertTrue(y(output, 220, 82) < 60) // expanded second box
                assertTrue(y(output, 8, 230) > 120) // outside both remains the input
            } finally { output.release() }
            faces = emptyList()
            val lost = compositor.compose(input, choice, true)!!
            try { assertTrue(y(lost, 8, 230) < 50) } finally { lost.release() }
        } finally { input.release(); compositor.close() }
    }

    @Test fun brokenArtworkStillPublishesAnOpaqueAvatar() {
        val missingArtwork = object : ContextWrapper(context) {
            override fun getResources(): Resources = throw Resources.NotFoundException("test artwork failure")
        }
        val compositor = FrameCompositor(missingArtwork)
        val input = green()
        try {
            val output = compositor.compose(input, BackgroundChoice(face = FacePrivacyMode.AVATAR), true)!!
            try {
                assertTrue(y(output, 0, 0) < 50)
                assertTrue(y(output, 160, 120) < 50)
            } finally { output.release() }
        } finally { input.release(); compositor.close() }
    }

    @Test fun anUnavailableOwnedModelKeepsTheWholePictureCovered() {
        val compositor = FrameCompositor(context)
        val input = green()
        try {
            val output = compositor.compose(input, BackgroundChoice(face = FacePrivacyMode.MASK), true)!!
            try { assertTrue(y(output, 8, 230) < 50) } finally { output.release() }
        } finally { input.release(); compositor.close() }
    }
}
