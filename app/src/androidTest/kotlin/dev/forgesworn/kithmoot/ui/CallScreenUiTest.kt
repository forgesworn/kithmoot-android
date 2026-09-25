package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Signal-style call view: who fills the screen, what is at hand, what is tucked away. */
class CallScreenUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val self = "11".repeat(32)
    private fun person(id: String, name: String, self: Boolean = false) =
        ParticipantTile(id, self, 1, listOf(TileTrack(id.take(62) + "dd", "camera-$id", Roles.CAMERA)), id.take(62) + "dd", false, name = name)

    private fun onCall(initial: RoomState, onLeaveCall: () -> Unit = {}, checks: () -> Unit) {
        var state by mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KithMootTheme {
                        RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                            modifier = Modifier.fillMaxSize(), chat = { Text("Chat here") },
                            onLeaveCall = { onLeaveCall(); state = state.copy(onCall = false) })
                    }
                }
            }
            ui.onNodeWithText("Call", useUnmergedTree = true).performClick()
            checks()
        }
    }

    @Test fun one_other_person_fills_the_call_with_four_controls_and_more() {
        var left = 0
        onCall(
            RoomState(roomId = "44".repeat(32), selfParticipant = self, onCall = true, cameraOn = true,
                tiles = listOf(person(self, "Alex", self = true), person("22".repeat(32), "Rowan"))),
            onLeaveCall = { left++ },
        ) {
        ui.onNodeWithText("Rowan").assertIsDisplayed()
        ui.onNodeWithText("You").assertIsDisplayed()
        for (label in listOf("Mic", "Camera", "More", "Leave call")) ui.onNodeWithText(label).assertIsDisplayed()
        ui.onAllNodesWithText("Leave call").assertCountEquals(1)
        // Everything else waits in the sheet.
        ui.onNodeWithText("Flip camera").assertDoesNotExist()
        ui.onNodeWithText("More").performClick()
        ui.onNodeWithText("Flip camera").assertIsDisplayed()
        ui.onNodeWithText("Share screen").assertIsDisplayed()
        ui.onNodeWithText("Hide my picture").performClick()
        ui.onNodeWithText("You").assertDoesNotExist()
        ui.onNodeWithText("More").performClick()
        ui.onNodeWithText("Show my picture").performClick()
        ui.onNodeWithText("You").assertIsDisplayed()
        ui.onNodeWithText("Leave call").performClick()
        ui.onNodeWithText("Chat here").assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, left) }
        }
    }

    @Test fun whoever_is_speaking_is_marked_in_words_as_well_as_colour() {
        val other = "22".repeat(32)
        onCall(
            RoomState(roomId = "45".repeat(32), selfParticipant = self, onCall = true, speaking = setOf(other),
                tiles = listOf(person(self, "Alex", self = true), person(other, "Rowan"))),
        ) {
            ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Speaking")).assertCountEquals(1)
        }
    }

    @Test fun three_others_share_the_screen_equally() {
        val others = listOf("Rowan", "Sam", "Kit").mapIndexed { index, name -> person("${index + 3}".repeat(64), name) }
        onCall(RoomState(roomId = "46".repeat(32), selfParticipant = self, onCall = true,
            tiles = listOf(person(self, "Alex", self = true)) + others)) {
        val sizes = listOf("Rowan", "Sam", "Kit").map { name ->
            ui.onNodeWithText(name).assertIsDisplayed()
            ui.onNode(hasClickAction() and hasAnyDescendant(hasText(name)) and !hasText(name)).fetchSemanticsNode().size
        }
        assertEquals("every tile in the grid is the same size", 1, sizes.toSet().size)
        // The layout toggle is for large calls only.
        ui.onNodeWithText("More").performClick()
        ui.onNodeWithText("Show everyone the same size").assertDoesNotExist()
        }
    }
}
