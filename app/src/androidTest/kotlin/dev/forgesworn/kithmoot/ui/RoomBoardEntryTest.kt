package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test

class RoomBoardEntryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun roomHeaderOpensItsBoardAndBackReturnsToTheSameChat() {
        val group = RoomState(roomId = "12".repeat(32), selfParticipant = "ab".repeat(32), name = "Workshop")
        val privateChat = group.copy(roomId = "34".repeat(32), name = "Rowan", privateConversation = true)
        val state = mutableStateOf(group)
        val boardAvailable = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KithMootTheme {
                        val room = state.value
                        RoomScreen(
                            room, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                            chat = { Text("Chat content for ${room.name}") },
                            roomBoard = if (boardAvailable.value) {
                                { back, _ -> TextButton(onClick = back) { Text("Board for ${room.name}") } }
                            } else null,
                        )
                    }
                }
            }
            compose.onNodeWithContentDescription("Room board").performClick()
            compose.onNodeWithText("Board for Workshop").assertIsDisplayed()
            compose.onNodeWithText("Chat content for Workshop").assertDoesNotExist()
            compose.onNodeWithText("Board for Workshop").performClick()
            compose.onNodeWithText("Chat content for Workshop").assertIsDisplayed()

            compose.onNodeWithContentDescription("Room board").performClick()
            compose.runOnIdle { state.value = privateChat }
            compose.onNodeWithText("Board for Workshop").assertDoesNotExist()
            compose.onNodeWithText("Chat content for Rowan").assertIsDisplayed()
            compose.onNodeWithContentDescription("Room board").performClick()
            compose.onNodeWithText("Board for Rowan").assertIsDisplayed()
            compose.onNodeWithText("Board for Rowan").performClick()
            compose.onNodeWithText("Chat content for Rowan").assertIsDisplayed()
            compose.runOnIdle { boardAvailable.value = false }
            compose.onNodeWithContentDescription("Room board").assertDoesNotExist()
        }
    }
}
