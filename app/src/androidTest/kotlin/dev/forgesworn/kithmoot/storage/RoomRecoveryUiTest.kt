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
        seedLegacyRoom(app, "Weekend workshop")
        scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].refreshSavedRooms() }
        ui.click("Weekend workshop")
        ui.room()
        val before = app.savedRooms.list().single()
        val identity = app.savedRooms.get(before.id)!!.identity(System.currentTimeMillis() / 1000)
        ui.click("Leave room")
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
        ui.click("Leave room")
        ui.home()
        ui.click("More options for Weekend workshop")
        ui.click("Remove from this phone")
        ui.await("removal dialog rendered") { ui.hasText("Remove Weekend workshop from this phone?") }
        ui.click("Keep room")
        assertEquals(1, app.savedRooms.list().size)
        ui.click("More options for Weekend workshop")
        ui.click("Remove from this phone")
        ui.await("local removal confirmation rendered") { ui.hasText("Remove Weekend workshop from this phone?") }
        ui.click("Remove from this phone")
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

    /** design-home-rooms.md Q11, AC 31: back in a room with no call goes up
     *  a level to the rooms, rather than sending the app to the background. */
    @Test fun system_back_in_a_room_with_no_call_returns_to_the_rooms_list() {
        val scenario = activity.scenario
        ui.home()
        seedLegacyRoom(app, "Weekend workshop")
        scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].refreshSavedRooms() }
        ui.click("Weekend workshop")
        ui.room()

        androidx.test.espresso.Espresso.pressBack()

        ui.home()
        assertTrue("the activity must stay resumed, not finish", scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }

    /** design-home-rooms.md AC 28: Settings is pushed over home; back pops
     *  it, rather than leaving the app. */
    @Test fun system_back_from_settings_returns_to_home() {
        val scenario = activity.scenario
        ui.home()

        ui.click("Settings")
        ui.await("settings shown") { ui.hasText("Text size") }

        androidx.test.espresso.Espresso.pressBack()

        ui.await("home shown again") { ui.hasText("KithMoot") }
        assertTrue("the activity must stay resumed, not finish", scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
}
