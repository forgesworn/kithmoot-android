package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.ui.room.RoomSharingSheet
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RoomSharingUiTest {
    @get:Rule val compose = createComposeRule()
    private val alice = "11".repeat(32)
    private val bob = "22".repeat(32)

    @Test fun sharing_requires_a_person_and_explicit_start_and_an_edit_stops_it() {
        var state by mutableStateOf(RoomSharingState(candidates = listOf(alice, bob), relays = listOf("wss://fixture.invalid/")))
        var starts = 0
        compose.setContent { KithMootTheme {
            RoomSharingSheet(state, { key, approved -> state = state.copy(enabled = false,
                selected = if (approved) state.selected + key else state.selected - key) },
                { starts++; state = state.copy(enabled = true, previouslySaved = true) },
                { state = state.copy(enabled = false) }, {})
        } }
        compose.onNodeWithText("Start sharing").assertIsNotEnabled()
        compose.onNodeWithText("Nobody is approved automatically.", substring = true).assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf(alice), state.selected); assertFalse(state.enabled); assertEquals(0, starts) }
        compose.onNodeWithText("Start sharing").performClick()
        compose.onNodeWithText("Sharing is on").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop sharing").assertIsEnabled()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("Sharing is off").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf(alice, bob), state.selected); assertEquals(1, starts) }
    }

    @Test fun restored_people_and_connections_require_resume_and_stop_is_explicit() {
        var state by mutableStateOf(RoomSharingState(selected = setOf(alice), candidates = listOf(alice),
            relays = listOf("wss://selected.fixture.invalid/"), previouslySaved = true))
        var starts = 0; var stops = 0
        compose.setContent { KithMootTheme {
            RoomSharingSheet(state, { _, _ -> }, { starts++; state = state.copy(enabled = true) },
                { stops++; state = state.copy(enabled = false) }, {})
        } }
        compose.onNodeWithText("Sharing is off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("wss://selected.fixture.invalid/").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Resume sharing").performClick()
        compose.onNodeWithText("Stop sharing").performClick()
        compose.onNodeWithText("Sharing is off").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, stops); assertEquals(setOf(alice), state.selected) }
    }
}
