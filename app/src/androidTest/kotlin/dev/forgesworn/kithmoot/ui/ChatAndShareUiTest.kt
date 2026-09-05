package dev.forgesworn.kithmoot.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.graphics.Bitmap
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.util.Consumer
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.toggleReaction
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.ScreenShareViewer
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.webrtc.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic source only: never asks Android to capture a screen, camera or microphone. */
class ChatAndShareUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun chat_emoji_reactions_and_a_live_zoomable_picture_in_picture_viewer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("synthetic-presentation", source)
        val frames = Executors.newSingleThreadScheduledExecutor()
        var frameNumber = 0
        frames.scheduleAtFixedRate({
            val buffer = JavaI420Buffer.allocate(640, 360)
            val y = buffer.dataY
            for (row in 0 until 360) for (col in 0 until 640) y.put(row * buffer.strideY + col, (if (col < 320) 190 else 45 + frameNumber % 90).toByte())
            for (i in 0 until buffer.dataU.capacity()) buffer.dataU.put(i, 100.toByte())
            for (i in 0 until buffer.dataV.capacity()) buffer.dataV.put(i, 150.toByte())
            val frame = VideoFrame(buffer, 0, System.nanoTime()); source.capturerObserver.onFrameCaptured(frame); frame.release(); frameNumber++
        }, 0, 100, TimeUnit.MILLISECONDS)
        var phase by mutableIntStateOf(0)
        var live by mutableStateOf<VideoTrack?>(track)
        var pip by mutableStateOf(false)
        val self = "01".repeat(32)
        val first = ChatMessage("first", "02".repeat(32), "03".repeat(32), "Bring the blue toolbox", 1800000000, "Rowan")
        var messages by mutableStateOf(listOf(first))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity {
                    activity = it
                    it.addOnPictureInPictureModeChangedListener(Consumer<PictureInPictureModeChangedInfo> { info -> pip = info.isInPictureInPictureMode })
                    it.setContent {
                        KithMootTheme {
                            when (phase) {
                                0 -> ChatPane(messages, self, { body -> messages = messages + first.copy(id = "sent", participant = self, body = body) }, Modifier.fillMaxSize().systemBarsPadding(),
                                    onReact = { target, emoji -> messages = messages + first.copy(id = "reaction-${messages.size}", participant = self, reaction = toggleReaction(messages, target, self, emoji)) })
                                1 -> ScreenShareViewer(live, egl, "Synthetic workshop presentation", pip,
                                    onPopOut = { assertTrue(activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())) },
                                    onClose = { phase = 2 })
                                else -> Text("Viewer closed; call track retained")
                            }
                        }
                    }
                }
                ui.onNodeWithText("Bring the blue toolbox").assertIsDisplayed()
                ui.onNodeWithText("Search messages or people").performTextInput("absent")
                ui.onNodeWithText("No matching messages.").assertIsDisplayed()
                ui.onNodeWithText("Clear").performClick()
                ui.onNodeWithText("Say something").performTextInput("Hello ")
                ui.onNodeWithText("😊 Emoji").performClick()
                ui.onNodeWithText("Search emoji").performTextInput("facepalm")
                ui.onNodeWithContentDescription("🤦 facepalm head against wall").performClick()
                ui.onNodeWithContentDescription("Send").performClick()
                ui.onNodeWithText("Hello 🤦").assertIsDisplayed()
                ui.onAllNodesWithContentDescription("Add ❤️ reaction, 0").onFirst().performClick()
                ui.onNodeWithContentDescription("Remove ❤️ reaction, 1").assertExists().performClick()
                assertEquals(false, messages.last().reaction?.active)
                screenshot("chat")
                ui.runOnIdle { phase = 1 }
                ui.onNodeWithText("Synthetic workshop presentation").assertIsDisplayed()
                ui.onNodeWithContentDescription("Zoom in").performClick()
                ui.onNodeWithText("125%").assertIsDisplayed()
                ui.onNodeWithContentDescription("Zoom in").performClick()
                ui.onNodeWithContentDescription("Shared screen, 156 percent zoom. Pinch to zoom and drag to pan.").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(80f, 40f)) }
                ui.onNodeWithText("Fit to screen").performClick()
                ui.onNodeWithText("100%").assertIsDisplayed()
                // Captured source frames can predate this viewer. Wait for the
                // expected colours on the composed screen, not a source count.
                val viewport = ui.onNodeWithContentDescription("Shared screen, 100 percent zoom. Pinch to zoom and drag to pan.").fetchSemanticsNode().boundsInWindow
                var left = android.graphics.Color.BLACK
                var right = android.graphics.Color.BLACK
                ui.waitUntil(20_000) {
                    val bitmap = screenshot("viewer")
                    left = bitmap.getPixel((viewport.left + viewport.width / 3).toInt(), viewport.center.y.toInt())
                    right = bitmap.getPixel((viewport.left + viewport.width * 2 / 3).toInt(), viewport.center.y.toInt())
                    bitmap.recycle()
                    android.graphics.Color.red(left) > 180 &&
                        android.graphics.Color.red(left) > android.graphics.Color.blue(left) + 30 &&
                        android.graphics.Color.red(left) > android.graphics.Color.red(right) + 30
                }
                assertNotEquals("The shared picture must render, not just negotiate", left, right)
                ui.onNodeWithText("Pop out").performClick()
                ui.waitUntil(20_000) { pip }
                ui.onNodeWithText("Synthetic workshop presentation").assertDoesNotExist()
                ui.onNodeWithText("Pop out").assertDoesNotExist()
                ui.waitUntil(20_000) {
                    var movingPicture = false
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val texture = findTexture(activity.window.decorView)
                        val picture = texture?.getBitmap(32, 18)
                        movingPicture = picture != null && picture.getPixel(8, 9) != picture.getPixel(24, 9)
                        picture?.recycle()
                    }
                    movingPicture
                }
                // Synchronise Compose before checking the platform window: a
                // live TextureView bitmap alone does not prove it is visible.
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val serviceInfo = automation.serviceInfo
                serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automation.serviceInfo = serviceInfo
                ui.waitUntil(20_000) {
                    val window = automation.windows.firstOrNull { it.isInPictureInPictureMode }
                    if (window == null) false else {
                        val bounds = android.graphics.Rect()
                        window.getBoundsInScreen(bounds)
                        val picture = screenshot("pip")
                        val y = bounds.centerY().coerceIn(0, picture.height - 1)
                        val left = picture.getPixel((bounds.left + bounds.width() / 4).coerceIn(0, picture.width - 1), y)
                        val right = picture.getPixel((bounds.left + bounds.width() * 3 / 4).coerceIn(0, picture.width - 1), y)
                        picture.recycle()
                        android.graphics.Color.red(left) > 180 &&
                            android.graphics.Color.red(left) > android.graphics.Color.blue(left) + 30 &&
                            android.graphics.Color.red(left) > android.graphics.Color.red(right) + 30
                    }
                }
                // A fresh explicit intent expands the PiP task. Restore the
                // launch intent afterwards: ActivityScenario matches lifecycle
                // callbacks by that intent, while MainActivity retains new ones.
                val launchIntent = Intent(activity.intent)
                scenario.onActivity { it.startActivity(Intent(it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                ui.waitUntil(20_000) { !pip }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = launchIntent }
                ui.runOnIdle { live = null }
                ui.onNodeWithText("Screen sharing has stopped or is reconnecting.").assertIsDisplayed()
                ui.runOnIdle { live = track }
                ui.onNodeWithText("Close viewer").performClick()
                ui.onNodeWithText("Viewer closed; call track retained").assertIsDisplayed()
                assertEquals(MediaStreamTrack.State.LIVE, track.state())
                scenario.onActivity { it.finishAndRemoveTask() }
            }
        } finally {
            frames.shutdownNow(); frames.awaitTermination(5, TimeUnit.SECONDS)
            track.dispose(); source.dispose(); factory.dispose(); egl.release()
        }
    }
    private fun findTexture(view: android.view.View): android.view.TextureView? {
        if (view is android.view.TextureView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findTexture(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun screenshot(name: String): Bitmap {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.getExternalFilesDir(null), "chat-share-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}
