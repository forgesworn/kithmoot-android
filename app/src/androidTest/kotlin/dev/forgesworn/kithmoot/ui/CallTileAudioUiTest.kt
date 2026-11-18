package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.media.*
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CallTileAudioUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val self = "11".repeat(32)
    private val other = "22".repeat(32)
    private val me = ParticipantTile(self, true, 1, emptyList(), "phone", true, name = "Alex")
    private val peer = ParticipantTile(other, false, 1, emptyList(), "laptop", false, name = "Rowan")
    private val trace = listOf(0f, -0.04f, 0.02f, -0.16f, 0.22f, -0.08f, 0.03f, 0f)
    private fun meter(name: String) = ui.onNodeWithTag("audio-$name microphone", useUnmergedTree = true)
    private fun audio(local: Double?, remote: Double?) = CallAudioActivity(local,
        if (local == null) emptyList() else trace,
        remote?.let { mapOf("laptop" to AudioReading(it, trace)) } ?: emptyMap(), "call")
    private fun screenshot(name: String) {
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun alternating_speakers_keep_their_audio_strip_and_picture_in_place() {
        val levels = MutableStateFlow(audio(0.0, 0.2))
        var state by mutableStateOf(RoomState(roomId = "call", selfParticipant = self, selfDevice = "phone",
            onCall = true, micOn = true, speaking = setOf(other), tiles = listOf(me, peer)))
        var dark by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme(darkTheme = dark) {
                RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                    chat = { Text("Chat here") }, audioActivity = levels)
            } } }
            ui.onNodeWithText("Return").performClick()
            val stage = ui.onNodeWithTag("call-video-stage").fetchSemanticsNode().boundsInRoot
            val bounds = listOf("Alex", "Rowan").associateWith { meter(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            val names = listOf("Alex (you)", "Rowan").associateWith { ui.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            for (speakers in listOf(setOf(self), setOf(self, other), emptySet(), setOf(other))) {
                ui.runOnIdle {
                    state = state.copy(speaking = speakers)
                    levels.value = audio(if (self in speakers) 0.2 else 0.0, if (other in speakers) 0.2 else 0.0)
                }
                assertEquals(stage, ui.onNodeWithTag("call-video-stage").fetchSemanticsNode().boundsInRoot)
                for ((name, before) in bounds) assertEquals("$name's mic moved", before, meter(name).fetchSemanticsNode().boundsInRoot)
                for ((name, before) in names) assertEquals("$name's label moved", before, ui.onNodeWithText(name).fetchSemanticsNode().boundsInRoot)
                meter("Alex").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (self in speakers) "Speaking" else "Quiet"))
                meter("Rowan").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (other in speakers) "Speaking" else "Quiet"))
                ui.onNodeWithContentDescription("Microphone").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
                ui.onAllNodesWithContentDescription("Muted", useUnmergedTree = true).assertCountEquals(0)
            }
            ui.onNodeWithTag("audio-Incoming audio").assertDoesNotExist()
            screenshot("call-tile-audio-portrait.png")
            ui.runOnIdle { dark = true }
            for ((name, before) in bounds) assertEquals(before, meter(name).fetchSemanticsNode().boundsInRoot)
            screenshot("call-tile-audio-dark.png")
            ui.runOnIdle { state = state.copy(tiles = listOf(me, peer.copy(micMuted = true))) }
            meter("Rowan").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Muted"))
            meter("Alex").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Quiet"))
            ui.onNodeWithContentDescription("Muted", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun narrow_phone_with_large_text_keeps_both_meters_inside_the_video_above_controls() {
        val levels = MutableStateFlow(audio(0.2, 0.0))
        val state = RoomState(roomId = "call", selfParticipant = self, selfDevice = "phone",
            onCall = true, micOn = true, speaking = setOf(self), tiles = listOf(me, peer))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                val density = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.8f)) {
                    androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                        RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                            chat = { Text("Chat here") }, audioActivity = levels)
                    }
                }
            } } }
            ui.onNodeWithText("Return").performClick()
            val stage = ui.onNodeWithTag("call-video-stage").fetchSemanticsNode().boundsInRoot
            for (name in listOf("Alex", "Rowan")) {
                val bounds = meter(name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("$name meter escaped its video area", bounds.left >= stage.left && bounds.right <= stage.right && bounds.bottom <= stage.bottom)
            }
            screenshot("call-tile-audio-large-text.png")
            for (label in listOf("Mute", "Camera", "Chat", "More", "Leave")) {
                val text = ui.onNodeWithText(label).assertIsDisplayed()
                assertTrue(text.fetchSemanticsNode().boundsInRoot.top >= stage.bottom)
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("$label must remain a readable single line", 1, layouts.single().lineCount)
                val layout = layouts.single()
                assertFalse("$label must not be ellipsised", layout.isLineEllipsized(0))
                assertFalse("$label must fit vertically", layout.didOverflowHeight)
                // Centre alignment can retain the wider cached paragraph's
                // origin. Compare glyph width with the displayed node width.
                val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
                val nodeWidth = text.fetchSemanticsNode().boundsInRoot.width
                assertTrue("$label text width $lineWidth exceeds its $nodeWidth pixel node", lineWidth <= nodeWidth + 1f)
            }
        }
    }
}
