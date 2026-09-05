package dev.forgesworn.kithmoot.storage

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.RoomViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomRecoveryUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val startAction get() = hasText("Start a room") and hasClickAction()

    private fun awaitHome() = ui.waitUntil(60_000) {
        ui.onAllNodesWithText("KithMoot").fetchSemanticsNodes().isNotEmpty() &&
            ui.onAllNodes(hasContentDescription("Loading rooms")).fetchSemanticsNodes().isEmpty()
    }
    private fun awaitRoom() = ui.waitUntil(60_000) {
        ui.onAllNodesWithText("Leave").fetchSemanticsNodes().isNotEmpty()
    }
    private fun createRoom() {
        ui.onNodeWithText("Relay settings").performScrollTo().performClick()
        ui.onNodeWithText("Relays, one per line").performTextReplacement("ws://10.0.2.2:7777")
        ui.onNodeWithText("Room name (optional)").performScrollTo().performTextInput("Weekend workshop")
        ui.onNode(startAction).performScrollTo().performClick()
        awaitRoom()
    }

    @Test fun saved_room_recovery_and_corrupt_storage_keep_destructive_actions_explicit() {
        app.savedRooms.reset()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitHome()
            createRoom()
            val before = app.savedRooms.list().single()
            val identity = app.savedRooms.get(before.id)!!.identity(System.currentTimeMillis() / 1000)
            ui.onNodeWithText("Leave").performClick()
            awaitHome()
            ui.onNode(hasText("Weekend workshop") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
            awaitRoom()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[RoomViewModel::class.java].room.value
                assertEquals(identity.participant, state.selfParticipant)
                assertEquals(identity.devicePubkey, state.selfDevice)
                assertFalse(state.micOn)
                assertFalse(state.cameraOn)
                assertTrue(state.canRotateInvitation)
            }
            ui.onNodeWithText("Leave").performClick()
            awaitHome()
            ui.onNodeWithContentDescription("Rename Weekend workshop").performScrollTo().performClick()
            ui.onNodeWithText("Room name", substring = false).performTextReplacement("Garden group")
            ui.onNodeWithText("Save name").performClick()
            ui.waitUntil(60_000) { app.savedRooms.list().single().name == "Garden group" }
            ui.onNodeWithContentDescription("Forget Garden group").performScrollTo().performClick()
            ui.onNodeWithText("Keep room").performClick()
            assertEquals(1, app.savedRooms.list().size)
            ui.onNodeWithContentDescription("Forget Garden group").performScrollTo().performClick()
            ui.onNodeWithText("Forget room").performClick()
            ui.waitUntil(60_000) { app.savedRooms.list().isEmpty() }
            awaitHome()
        }
        corruptSavedDataKeepsEntryDisabledUntilExplicitDeletion()
    }

    private fun corruptSavedDataKeepsEntryDisabledUntilExplicitDeletion() {
        app.savedRooms.reset()
        val file = File(app.noBackupFilesDir, "kithmoot.rooms.v1.vault")
        file.writeText("broken ciphertext")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: RoomViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
            ui.waitUntil(60_000) { !model.start.value.loadingRooms }
            assertTrue("Corrupt storage must block entry", model.start.value.storageError)
            ui.waitUntil(60_000) { ui.onAllNodesWithText("Saved rooms are unavailable").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Saved rooms are unavailable").performScrollTo().assertIsDisplayed()
            ui.onNode(startAction).assertIsNotEnabled()
            ui.onNodeWithText("Delete saved rooms…").performClick()
            ui.onNodeWithText("Keep saved data").performClick()
            assertEquals("broken ciphertext", file.readText())
            ui.onNodeWithText("Delete saved rooms…").performClick()
            ui.onNodeWithText("Delete saved rooms", substring = false).performClick()
            awaitHome()
            ui.onNode(startAction).assertIsEnabled()
        }
    }
}
