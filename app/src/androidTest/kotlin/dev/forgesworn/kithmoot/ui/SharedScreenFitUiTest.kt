package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.room.ScreenShareViewer
import dev.forgesworn.kithmoot.ui.room.VideoSurface
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A 3840-pixel desktop with bright borders proves that fit preserves its edges,
 * rather than merely checking that the renderer has received a frame. */
class SharedScreenFitUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    private fun allEdgesVisible(bounds: Rect): Boolean {
        val picture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        try {
            val width = bounds.width
            val height = width / (3840f / 1080f)
            val top = bounds.center.y - height / 2
            fun bright(x: Float, y: Float) = android.graphics.Color.red(picture.getPixel(x.toInt(), y.toInt())) > 190
            return bright(bounds.left + width * .015f, bounds.center.y) &&
                bright(bounds.right - width * .015f, bounds.center.y) &&
                bright(bounds.center.x, top + height * .015f) &&
                bright(bounds.center.x, top + height * .985f) &&
                !bright(bounds.center.x, bounds.center.y)
        } finally { picture.recycle() }
    }

    @Test fun large_desktop_fits_tile_and_viewer_and_a_new_share_resets_zoom() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("large-synthetic-desktop", source)
        val frames = Executors.newSingleThreadScheduledExecutor()
        frames.scheduleAtFixedRate({
            val buffer = JavaI420Buffer.allocate(3840, 1080)
            for (row in 0 until 1080) for (col in 0 until 3840) {
                val border = col < 150 || col >= 3690 || row < 42 || row >= 1038
                buffer.dataY.put(row * buffer.strideY + col, (if (border) 235 else 50).toByte())
            }
            for (i in 0 until buffer.dataU.capacity()) buffer.dataU.put(i, 128.toByte())
            for (i in 0 until buffer.dataV.capacity()) buffer.dataV.put(i, 128.toByte())
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            source.capturerObserver.onFrameCaptured(frame); frame.release()
        }, 0, 500, TimeUnit.MILLISECONDS)
        var expanded by mutableStateOf(false)
        var shareId by mutableStateOf("first-share")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    KithMootTheme {
                        if (expanded) ScreenShareViewer(track, egl, "Large desktop", false, null, {}, shareId = shareId)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            VideoSurface(track, egl, Modifier.size(220.dp).semantics { contentDescription = "Fitted share tile" }, fill = false)
                        }
                    }
                } }
                ui.waitUntil(20_000) { allEdgesVisible(ui.onNodeWithContentDescription("Fitted share tile").fetchSemanticsNode().boundsInWindow) }
                ui.runOnIdle { expanded = true }
                fun viewport() = ui.onNodeWithContentDescription("Shared screen, 100 percent zoom. Pinch to zoom and drag to pan.").fetchSemanticsNode().boundsInWindow
                ui.waitUntil(20_000) { allEdgesVisible(viewport()) }
                ui.onNodeWithContentDescription("Zoom in").performClick()
                ui.onNodeWithText("125%").assertIsDisplayed()
                ui.runOnIdle { shareId = "second-share" }
                ui.onNodeWithText("100%").assertIsDisplayed()
                ui.waitUntil(20_000) { allEdgesVisible(viewport()) }
                assertTrue("The shared desktop must retain all four edges", allEdgesVisible(viewport()))
            }
        } finally {
            frames.shutdownNow(); frames.awaitTermination(5, TimeUnit.SECONDS)
            track.dispose(); source.dispose(); factory.dispose(); egl.release()
        }
    }
}
