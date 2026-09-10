package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import android.graphics.Bitmap
import java.io.File
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Switching views must not start media or discard an unfinished conversation. */
class RoomWorkspaceUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun chat_is_primary_and_draft_survives_call_controls() {
        var mediaRequests = 0
        var sent = ""
        val state = RoomState(roomId = "01".repeat(32), selfParticipant = "02".repeat(32), name = "Workshop")
        val messages = (1..40).map { ChatMessage("message-$it", "03".repeat(32), "04".repeat(32), "Update $it", 1800000000L + it, "Rowan") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KithMootTheme {
                        RoomScreen(
                            state = state, videos = emptyMap(), eglBase = null,
                            onToggleMic = { mediaRequests++ }, onToggleAgentsMayHear = { mediaRequests++ },
                            onToggleCamera = { mediaRequests++ }, onSwitchCamera = {},
                            onToggleScreenShare = { mediaRequests++ }, onAddDevice = {},
                            onRotateInvitation = {}, onLeave = {},
                            chat = { ChatPane(messages, state.selfParticipant, { sent = it }, Modifier.fillMaxSize(), showTitle = false) },
                        )
                    }
                }
            }
            ui.onNodeWithText("Say something").assertIsDisplayed().performTextInput("Keep this thought while I check the call")
            ui.onNodeWithText("Search messages or people").performTextInput("release")
            ui.onNodeWithText("Call").performClick()
            ui.onNodeWithText("Say something").assertDoesNotExist()
            ui.runOnIdle { assertEquals(0, mediaRequests) }
            ui.onNode(hasText("Chat") and hasClickAction()).performClick()
            ui.onNodeWithText("Keep this thought while I check the call").assertIsDisplayed()
            ui.onNodeWithText("release").assertIsDisplayed()
            ui.onNodeWithContentDescription("Send").performClick()
            ui.runOnIdle {
                assertEquals("Keep this thought while I check the call", sent)
                assertEquals(0, mediaRequests)
            }
            ui.onNodeWithText("Clear").performClick()
            Espresso.closeSoftKeyboard()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Update 5"))
            ui.onNodeWithText("Update 5").assertIsDisplayed()
            ui.onNodeWithText("Call").performClick()
            ui.onNode(hasText("Chat") and hasClickAction()).performClick()
            ui.onNodeWithText("Update 5").assertIsDisplayed()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val image = instrumentation.uiAutomation.takeScreenshot()
            val file = File(instrumentation.targetContext.getExternalFilesDir("ui-proof"), "room-workspace.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
