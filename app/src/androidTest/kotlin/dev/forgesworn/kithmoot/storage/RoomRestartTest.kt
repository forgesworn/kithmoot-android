package dev.forgesworn.kithmoot.storage

import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.RoomViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Properties

/** Each method runs in its own instrumentation process, with a force-stop
 * between them and requireRestart=true to prove a different process. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RoomRestartTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val marker get() = File(app.noBackupFilesDir, "restart-test.properties")

    @Test fun a_prepare() {
        ui.home()
        app.savedRooms.reset()
        activity.scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].refreshSavedRooms() }
        ui.home()
        ui.click("Relay settings")
        ui.replace("Relays, one per line", "ws://10.0.2.2:59999")
        ui.replace("Room name (optional)", "Restart workshop")
        ui.click("Group: come back any time")
        ui.click("Start a room")
        ui.room()
        val saved = app.savedRooms.get(app.savedRooms.list().single().id)!!
        val identity = saved.identity(System.currentTimeMillis() / 1000)
        val expected = Properties().apply {
            setProperty("id", saved.id)
            setProperty("participant", identity.participant)
            setProperty("device", identity.devicePubkey)
            setProperty("pid", Process.myPid().toString())
        }
        marker.outputStream().use { expected.store(it, "Synthetic restart-test identifiers only") }
    }

    @Test fun b_reopen() {
        assertTrue("Run a_prepare first", marker.isFile)
        val expected = Properties().apply { marker.inputStream().use { load(it) } }
        if (InstrumentationRegistry.getArguments().getString("requireRestart") == "true") {
            assertNotEquals(expected.getProperty("pid"), Process.myPid().toString())
        }
        ui.home()
        ui.click("Restart workshop")
        ui.room()
        activity.scenario.onActivity { activity ->
            val state = ViewModelProvider(activity)[RoomViewModel::class.java].room.value
            assertEquals(expected.getProperty("id"), state.roomId)
            assertEquals(expected.getProperty("participant"), state.selfParticipant)
            assertEquals(expected.getProperty("device"), state.selfDevice)
            assertTrue(state.canRotateInvitation)
            assertFalse(state.micOn)
            assertFalse(state.cameraOn)
            assertFalse(state.screenOn)
        }
    }
}
