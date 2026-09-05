package dev.forgesworn.kithmoot.storage

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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

/** Run each method in its own instrumentation invocation with a force-stop between
 * them and `-e requireRestart true` on b_reopen to prove a different process. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RoomRestartTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val marker get() = File(app.noBackupFilesDir, "restart-test.properties")
    private val startAction get() = hasText("Start a room") and hasClickAction()
    private fun home() {
        // A fresh CI process loads Keystore, crypto and Compose classes. This
        // checks readiness, not startup speed, and a saved list can put the
        // new-room button below the viewport.
        try {
            ui.waitUntil(60_000) {
                ui.onAllNodesWithText("KithMoot").fetchSemanticsNodes().isNotEmpty() &&
                    ui.onAllNodesWithContentDescription("Loading rooms").fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: Exception) {
            throw AssertionError("Home did not become ready:\n" + ui.onRoot(useUnmergedTree = true).printToString(), failure)
        }
    }
    private fun room() = ui.waitUntil(20_000) { ui.onAllNodesWithText("Leave").fetchSemanticsNodes().isNotEmpty() }

    @Test fun a_prepare() {
        app.savedRooms.reset()
        ActivityScenario.launch(MainActivity::class.java).use {
            home()
            ui.onNodeWithText("Relay settings").performScrollTo().performClick()
            ui.onNodeWithText("Relays, one per line").performTextReplacement("ws://10.0.2.2:7777")
            ui.onNodeWithText("Room name (optional)").performScrollTo().performTextInput("Restart workshop")
            ui.onNode(startAction).performScrollTo().performClick()
            room()
            val saved = app.savedRooms.get(app.savedRooms.list().single().id)!!
            val identity = saved.identity(System.currentTimeMillis() / 1000)
            val expected = Properties().apply {
                setProperty("id", saved.id)
                setProperty("participant", identity.participant)
                setProperty("device", identity.devicePubkey)
                setProperty("pid", Process.myPid().toString())
            }
            marker.outputStream().use { output -> expected.store(output, "Synthetic restart-test identifiers only") }
        }
    }

    @Test fun b_reopen() {
        assertTrue("Run a_prepare first", marker.isFile)
        val expected = Properties().apply { marker.inputStream().use { load(it) } }
        if (InstrumentationRegistry.getArguments().getString("requireRestart") == "true") {
            assertNotEquals(expected.getProperty("pid"), Process.myPid().toString())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            home()
            ui.onNode(hasText("Restart workshop") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
            room()
            scenario.onActivity { activity ->
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
}
