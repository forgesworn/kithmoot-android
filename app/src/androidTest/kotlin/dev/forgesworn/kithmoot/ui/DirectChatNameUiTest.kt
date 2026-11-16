package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.ROOM_TITLE_TAG
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test

/** The actual name controls keep the counterpart title when the personal label resets. */
class DirectChatNameUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun personal_label_can_be_saved_and_reset_without_a_group_rename() {
        val peer = "03".repeat(32)
        var state by mutableStateOf(RoomState(roomId = "01".repeat(32), selfParticipant = "02".repeat(32),
            name = "Rowan", privateConversation = true, privatePeer = peer,
            profiles = mapOf(peer to PublicProfile("Taylor", null, 1, "profile"))))
        ui.setContent { KithMootTheme {
            RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                onRenameRoom = { state = state.copy(chatLabel = it.ifBlank { null }) }, chat = {})
        } }
        ui.onNodeWithTag(ROOM_TITLE_TAG).assertTextContains("Taylor").performClick()
        ui.onNodeWithText("Name and picture").performClick()
        ui.onNodeWithText("Your chat name").performTextInput("Taylor from work")
        ui.onNodeWithText("Save name for me").performClick()
        ui.onNodeWithText("Use their name").assertIsDisplayed()
        ui.onNodeWithText("Rename for everyone").assertDoesNotExist()
        ui.onNodeWithText("Choose room logo").assertDoesNotExist()
        ui.onNodeWithText("Use their name").performClick()
        ui.onNodeWithText("Use their name").assertDoesNotExist()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithContentDescription("Close room info").performClick()
        ui.onNodeWithTag(ROOM_TITLE_TAG).assertTextContains("Taylor")
    }
}
