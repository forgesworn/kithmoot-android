package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RoomEpochUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun recovery_is_specific_retryable_and_read_only() {
        var retries = 0
        val state = RoomState(
            roomId = "01".repeat(32), selfParticipant = "02".repeat(32), name = "Secure workshop",
            movedOn = 3, roomUpdate = "recovery", notice = "The room authority is currently unavailable.",
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KithMootTheme {
                        RoomScreen(
                            state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                            chat = {
                                ChatPane(
                                    emptyList(), state.selfParticipant, {}, Modifier.fillMaxSize(),
                                    canSend = false, showTitle = false,
                                )
                            },
                            onRetryRoomUpdate = { retries++ },
                        )
                    }
                }
            }
            ui.onNodeWithText("Room update needs attention").assertIsDisplayed()
            ui.onNodeWithText("Retry secure update").performClick()
            ui.runOnIdle { assertEquals(1, retries) }
            ui.onNodeWithText("Call").performClick()
            ui.onAllNodesWithText("Mic").assertCountEquals(0)
            ui.onNodeWithText("Chat").performClick()
            ui.onNodeWithContentDescription("Send").assertIsNotEnabled()
        }
    }
}
