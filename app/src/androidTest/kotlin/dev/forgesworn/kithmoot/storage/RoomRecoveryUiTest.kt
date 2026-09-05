package dev.forgesworn.kithmoot.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
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
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()

    @Test fun saved_room_recovery_and_corrupt_storage_keep_destructive_actions_explicit() {
        val scenario = activity.scenario
        ui.home()
        app.savedRooms.reset()
        scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].refreshSavedRooms() }
        ui.home()
        ui.click("Relay settings")
        ui.replace("Relays, one per line", "ws://10.0.2.2:59999")
        ui.click("Group: come back any time")
        ui.replace("Room name (optional)", "Weekend workshop")
        ui.click("Start a room")
        ui.room()
        val before = app.savedRooms.list().single()
        val identity = app.savedRooms.get(before.id)!!.identity(System.currentTimeMillis() / 1000)
        ui.click("Leave")
        ui.home()
        ui.click("Weekend workshop")
        ui.room()
        scenario.onActivity { activity ->
            val state = ViewModelProvider(activity)[RoomViewModel::class.java].room.value
            assertEquals(identity.participant, state.selfParticipant)
            assertEquals(identity.devicePubkey, state.selfDevice)
            assertFalse(state.micOn)
            assertFalse(state.cameraOn)
            assertTrue(state.canRotateInvitation)
        }
        ui.click("Leave")
        ui.home()
        ui.click("Rename Weekend workshop")
        ui.replace("Room name", "Garden group")
        ui.click("Save name")
        ui.await("saved local name") { app.savedRooms.list().single().name == "Garden group" }
        ui.click("Forget Garden group")
        ui.click("Keep room")
        assertEquals(1, app.savedRooms.list().size)
        ui.click("Forget Garden group")
        ui.click("Forget room")
        ui.await("explicit room deletion") { app.savedRooms.list().isEmpty() }
        ui.home()

        app.savedRooms.reset()
        val file = File(app.noBackupFilesDir, "kithmoot.rooms.v1.vault")
        file.writeText("broken ciphertext")
        lateinit var model: RoomViewModel
        scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java]; model.refreshSavedRooms() }
        ui.await("corrupt storage result") { !model.start.value.loadingRooms }
        assertTrue("Corrupt storage must block entry", model.start.value.storageError)
        ui.await("visible corrupt storage error") { ui.hasText("Saved rooms are unavailable") }
        ui.assertEnabled("Start a room", false)
        ui.click("Delete saved rooms…")
        ui.click("Keep saved data")
        assertEquals("broken ciphertext", file.readText())
        ui.click("Delete saved rooms…")
        ui.click("Delete saved rooms")
        ui.home()
        ui.assertEnabled("Start a room", true)
    }
}
