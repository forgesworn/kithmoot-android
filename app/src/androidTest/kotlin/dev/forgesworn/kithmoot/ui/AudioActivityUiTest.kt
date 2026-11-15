package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.media.CallAudioActivity
import dev.forgesworn.kithmoot.ui.room.AudioActivityPanel
import dev.forgesworn.kithmoot.ui.room.AudioSignal
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class AudioActivityUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    private fun screenshot(name: String) {
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun sound_silence_and_mute_are_expressed_in_words_without_live_announcements() {
        val levels = MutableStateFlow(CallAudioActivity(0.1, 0.2, 1))
        val muted = mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme { AudioActivityPanel(levels, "test-call", true, muted.value) } } }
            ui.onNodeWithTag("audio-Your mic").assert(state("Sound"))
            ui.onNodeWithTag("audio-Incoming audio").assert(state("Sound"))
            ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
            ui.runOnIdle { muted.value = true; levels.value = CallAudioActivity(0.1, 0.0, 2) }
            ui.onNodeWithTag("audio-Your mic").assert(state("Muted"))
            ui.onNodeWithTag("audio-Incoming audio").assert(state("Quiet"))
            ui.runOnIdle { levels.value = CallAudioActivity(sample = 3) }
            ui.onNodeWithTag("audio-Incoming audio").assert(state("Waiting"))
        }
    }

    @Test fun reduced_motion_uses_a_level_bar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme {
                AudioSignal("Incoming audio", 0.1, 1, "test", null, reducedMotion = true)
            } } }
            ui.onNodeWithTag("audio-level", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithTag("audio-trace", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithTag("audio-Incoming audio").assert(state("Sound"))
        }
    }

    @Test fun activity_is_visible_in_chat_and_in_the_camera_off_call() {
        val levels = MutableStateFlow(CallAudioActivity(0.1, 0.2, 1))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KithMootTheme {
                RoomScreen(RoomState(roomId = "11".repeat(32), onCall = true, micOn = true), emptyMap(), null,
                    {}, {}, {}, {}, {}, {}, {}, {}, chat = { Text("Chat here") }, audioActivity = levels)
            } } }
            ui.onNodeWithTag("audio-Incoming audio").assertIsDisplayed()
            screenshot("audio-activity-chat.png")
            ui.onNodeWithText("Return").performClick()
            ui.onNodeWithTag("audio-Incoming audio").assertIsDisplayed()
            ui.onNodeWithTag("audio-Your mic").assertIsDisplayed()
            screenshot("audio-activity-call.png")
        }
    }
}
