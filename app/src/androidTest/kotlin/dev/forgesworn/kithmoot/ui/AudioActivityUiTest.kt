package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.media.AudioReading
import dev.forgesworn.kithmoot.media.CallAudioActivity
import dev.forgesworn.kithmoot.ui.room.AudioActivityPanel
import dev.forgesworn.kithmoot.ui.room.AudioSignal
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class AudioActivityUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    private fun screenshot(name: String) {
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun changing_audio_states_does_not_move_the_video_on_a_narrow_phone() {
        val level = mutableStateOf<Double?>(null)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme {
                Column(Modifier.width(135.dp)) {
                    AudioSignal("Rowan", level.value, null)
                    Box(Modifier.width(135.dp).height(100.dp).testTag("video-slot"))
                }
            } } }
            val before = ui.onNodeWithTag("video-slot").fetchSemanticsNode().boundsInRoot
            for (next in listOf(0.0, 0.1, null, 0.2, 0.0)) {
                ui.runOnIdle { level.value = next }
                val after = ui.onNodeWithTag("video-slot").fetchSemanticsNode().boundsInRoot
                assertEquals("Audio state changed the video bounds", before, after)
            }
        }
    }

    @Test fun sound_silence_and_mute_are_expressed_in_words_without_live_announcements() {
        val names = listOf("Rowan", "Sam", "Kit", "Avery", "In chat")
        val peers = names.map { name -> ParticipantTile(name, false, 1, emptyList(), name, false, name = name) }
        val call = RoomState(roomId = "test-call", micOn = true, tiles = peers,
            currentCall = CallSummary(null, 5, 0, listOf("self") + names.take(4)), selfParticipant = "self")
        val levels = MutableStateFlow(CallAudioActivity(0.1, microphones = mapOf("Rowan" to AudioReading(0.2),
            "Sam" to AudioReading(0.0), "Avery" to AudioReading(0.2), "In chat" to AudioReading(0.8))))
        val muted = mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme { AudioActivityPanel(levels, call.copy(micMuted = muted.value)) } } }
            ui.onNodeWithTag("audio-You").assert(state("Speaking"))
            ui.onNodeWithTag("audio-Rowan").assert(state("Speaking"))
            ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
            val strip = ui.onNodeWithTag("participant-microphones")
            strip.performScrollToIndex(4)
            ui.onNodeWithTag("audio-Avery").assertIsDisplayed().assert(state("Speaking"))
            ui.onNodeWithTag("audio-In chat").assertDoesNotExist()
            strip.performScrollToIndex(0)
            ui.runOnIdle { muted.value = true; levels.value = CallAudioActivity(0.1, microphones = mapOf("Rowan" to AudioReading(0.0))) }
            ui.onNodeWithTag("audio-You").assert(state("Muted"))
            ui.onNodeWithTag("audio-Rowan").assert(state("Quiet"))
            ui.runOnIdle { levels.value = CallAudioActivity() }
            ui.onNodeWithTag("audio-Rowan").assert(state("Waiting"))
        }
    }

    @Test fun reduced_motion_uses_a_level_bar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme {
                AudioSignal("Rowan", 0.1, null, reducedMotion = true)
            } } }
            ui.onNodeWithTag("audio-level", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithTag("audio-trace", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithTag("audio-Rowan").assert(state("Speaking"))
        }
    }

    @Test fun activity_is_visible_in_chat_and_in_the_camera_off_call() {
        val levels = MutableStateFlow(CallAudioActivity(0.1))
        val me = ParticipantTile("11".repeat(32), true, 1, emptyList(), "phone", true, name = "Alex")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme {
                RoomScreen(RoomState(roomId = "11".repeat(32), selfDevice = "phone", onCall = true, micOn = true, tiles = listOf(me)), emptyMap(), null,
                    {}, {}, {}, {}, {}, {}, {}, {}, chat = { Text("Chat here") }, audioActivity = levels)
            } } }
            ui.onNodeWithTag("audio-You").assertIsDisplayed().assert(state("Speaking"))
            screenshot("audio-activity-chat.png")
            ui.onNodeWithText("Return").performClick()
            ui.onNodeWithTag("audio-You").assertDoesNotExist()
            ui.onNodeWithTag("audio-Alex microphone", useUnmergedTree = true).assertIsDisplayed().assert(state("Speaking"))
            screenshot("audio-activity-call.png")
        }
    }
}
