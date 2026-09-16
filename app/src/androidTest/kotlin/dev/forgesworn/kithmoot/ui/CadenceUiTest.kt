package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CadenceUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun acknowledged_schedule_shows_queue_evidence_and_safe_stop() {
        var stopped = 0
        val cadence = CadenceViewState(
            eligible = true,
            state = "active",
            detail = "Bothy owns this device's quiet cadence and queued messages during the scheduled window.",
            startEpoch = 500002,
            endEpoch = 500014,
            queueCount = 2,
            sentCount = 1,
            failedCount = 0,
        )
        val state = RoomState(roomId = "01".repeat(32), selfParticipant = "02".repeat(32), name = "Quiet workshop", quiet = true, cadence = cadence)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KithMootTheme {
                        RoomScreen(
                            state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                            chat = { ChatPane(emptyList(), state.selfParticipant, {}, Modifier.fillMaxSize(), showTitle = false) },
                            onStopCadence = { stopped++ },
                        )
                    }
                }
            }
            ui.onNodeWithContentDescription("Room details").performClick()
            ui.onNodeWithText("Quiet schedule · active").assertIsDisplayed()
            ui.onNodeWithText("2 queued · 1 sent · 0 failed", substring = true).assertIsDisplayed()
            ui.onNodeWithText("Stop").performClick()
            ui.runOnIdle { assertEquals(1, stopped) }
        }
    }
}
