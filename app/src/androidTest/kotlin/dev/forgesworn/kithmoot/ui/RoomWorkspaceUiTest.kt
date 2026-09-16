package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
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
        var searchOpen by mutableStateOf(false)
        val state = RoomState(roomId = "01".repeat(32), selfParticipant = "02".repeat(32), name = "Workshop", relaysUp = 2, relaysTotal = 2, privateConversation = true, nip77 = Nip77ViewState())
        val latest = listOf("Morning! Have you had a chance to try the room on your phone?", "Yes, the messages came through.",
            "Great. I have added the notes to the project too.", "I will read them before our call.", "Shall we catch up at half past?", "Sounds good 👍")
        val messages = (1..40).map {
            val mine = it > 34 && it % 2 == 0
            ChatMessage("message-$it", if (mine) state.selfParticipant else "03".repeat(32), "04".repeat(32),
                if (it > 34) latest[it - 35] else "Update $it", 1800000000L + it * 90, if (mine) "Alex" else "Rowan",
                lane = dev.forgesworn.kithmoot.protocol.Lane.PUBLIC)
        }
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
                            onSearch = { searchOpen = !searchOpen },
                            accountMenu = { IconButton(onClick = {}) { dev.forgesworn.kithmoot.ui.room.ProfileAvatar(state.selfParticipant, "Alex", null, Modifier.size(36.dp)) } },
                            chat = { ChatPane(messages, state.selfParticipant, { sent = it }, Modifier.fillMaxSize(), showTitle = false, lane = dev.forgesworn.kithmoot.protocol.Lane.PUBLIC, searchOpen = searchOpen, onCloseSearch = { searchOpen = false }) },
                        )
                    }
                }
            }
            ui.onNodeWithText("Private history check").assertDoesNotExist()
            ui.onNodeWithText("Search messages or people").assertDoesNotExist()
            val viewport = ui.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
            val screen = ui.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Conversation should occupy most of the room screen", viewport.height > screen.height * 0.60f)
            ui.onNodeWithContentDescription("Room details").performClick()
            ui.onNodeWithText("Private history check").assertIsDisplayed()
            ui.onNodeWithText("Done").performClick()
            ui.onNodeWithText("Say something").assertIsDisplayed().performTextInput("Keep this thought while I check the call")
            ui.onNodeWithContentDescription("Search messages").performClick()
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
            ui.onNodeWithContentDescription("Close search").performClick()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Sounds good 👍"))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val image = instrumentation.uiAutomation.takeScreenshot()
            val file = File(instrumentation.targetContext.getExternalFilesDir("ui-proof"), "room-workspace.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
