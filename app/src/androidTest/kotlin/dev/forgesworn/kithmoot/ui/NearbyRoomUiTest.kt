package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.start.StartScreen
import dev.forgesworn.kithmoot.ui.start.InviteLinkSection
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NearbyRoomUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fresh_nearby_choice_explains_local_identity_before_submitting_the_code() {
        var nearby: String? = null
        var internet = 0
        compose.setContent { KithMootTheme {
            InviteLinkSection("https://fixture.invalid/j/#invitation", {}, true, { internet++ },
                collapsible = false, onJoinNearby = { nearby = it })
        } }
        compose.onNodeWithText("Join nearby with Bluetooth").performClick()
        compose.onNodeWithText("You will join with a new identity kept on this phone. Your account's other activity keeps its own connection settings.").assertIsDisplayed()
        compose.onNodeWithText("Nearby code").performTextInput("fixture-code")
        compose.runOnIdle { assertNull(nearby); assertEquals(0, internet) }
        compose.onNodeWithText("Join with local identity").performClick()
        compose.runOnIdle { assertEquals("fixture-code", nearby); assertEquals(0, internet) }
    }

    @Test fun connection_is_chosen_before_room_entry_and_the_row_keeps_the_choice() {
        var saved by mutableStateOf(SavedRoomSummary("room", "Nearby fixture", false, System.currentTimeMillis() / 1000))
        var opened: Pair<String, RoomRoute>? = null
        compose.setContent {
            KithMootTheme {
                StartScreen(state = StartState(loadingRooms = false, savedRooms = listOf(saved)),
                    onRoomNameChanged = {}, onJoinUrlChanged = {}, onRelaysChanged = {},
                    onAnonymousModeChanged = {}, onPersistentGroupChanged = {}, onStartRoom = {}, onJoin = {},
                    onReopen = { opened = it to saved.route }, onForget = {}, onProject = { _, _ -> },
                    onRetryStorage = {}, onResetStorage = {},
                    onRoomRoute = { id, route -> assertEquals(saved.id, id); saved = saved.copy(route = route) })
            }
        }
        compose.onNodeWithContentDescription("More options for Nearby fixture").performClick()
        compose.onNodeWithText("Connection: Internet").performClick()
        compose.onNodeWithText("Nearby only").performClick()
        compose.runOnIdle { assertNull(opened); assertEquals(RoomRoute.NEARBY, saved.route) }
        compose.onNodeWithContentDescription("More options for Nearby fixture").performClick()
        compose.onNodeWithText("Connection: Nearby only").performClick()
        compose.onNodeWithText("✓ Nearby only").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Nearby fixture").performClick()
        compose.runOnIdle { assertEquals("room" to RoomRoute.NEARBY, opened) }
    }
}
