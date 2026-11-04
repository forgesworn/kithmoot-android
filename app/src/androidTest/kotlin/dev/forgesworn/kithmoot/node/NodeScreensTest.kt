package dev.forgesworn.kithmoot.node

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.NodeJoinRequest
import dev.forgesworn.kithmoot.ui.room.NodeAdmissionPanel
import dev.forgesworn.kithmoot.ui.room.NodeRoomPanel
import dev.forgesworn.kithmoot.ui.settings.NodePage
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Public synthetic states for layout/confirmation evidence, separate from
 * NodeNativeMediaTest's actual-service integration journey. */
class NodeScreensTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        val directory = File(instrumentation.targetContext.filesDir, "node-ui-evidence").apply { mkdirs() }
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            val colours = mutableSetOf<Int>()
            for (y in bitmap.height / 8 until bitmap.height * 7 / 8 step 8)
                for (x in bitmap.width / 8 until bitmap.width * 7 / 8 step 8) colours += bitmap.getPixel(x, y)
            assertTrue("UI evidence must contain rendered content", colours.size > 20)
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun pairedStatusAndLocalUnpairNeedExplicitConfirmation() {
        var unpaired = 0
        val pair = NodePair("https://node.example", "1".repeat(64), "2".repeat(64))
        val view = NodeSettingsView(account = pair.owner, pair = pair,
            status = NodeStatus("0.1.0-rc.1", true, true, 1, 2, 0, true, true, 1048576))
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Your KithMoot node", style = MaterialTheme.typography.headlineSmall)
            NodePage(view, {}, { unpaired++ }, {}, {})
        } } } }
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("1 rooms · 2 devices · 0 pending closures").assertIsDisplayed()
        capture("android-node-settings")
        compose.onNodeWithText("Unpair on this device").performScrollTo().performClick()
        assertEquals(0, unpaired)
        capture("android-node-unpair-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, unpaired)
        compose.onNodeWithText("Unpair on this device").performScrollTo().performClick()
        compose.onNodeWithText("Unpair").performClick()
        assertEquals(1, unpaired)
    }
    @Test fun keeperAdmissionRemovalAndClosureAreExplicit() {
        val answers = mutableListOf<Pair<String, Boolean>>()
        val removed = mutableListOf<String>()
        var closes = 0
        val request = NodeJoinRequest("a".repeat(64), "b".repeat(64), "c".repeat(64), JsonObject(emptyMap()), "Guest", 1900000600)
        val view = NodeHostedRoomView("node.example", true, members = listOf("d".repeat(64) to "Admitted guest"), requests = listOf(request))
        compose.setContent { MaterialTheme { Surface { Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Room details", style = MaterialTheme.typography.headlineSmall)
            NodeAdmissionPanel(view) { id, allow -> answers += id to allow }
            NodeRoomPanel(view, { removed += it }, { closes++ })
        } } } }
        compose.onNodeWithText("Hosted on").assertIsDisplayed()
        capture("android-node-hosted-room")
        compose.onNodeWithText("Admit").performClick()
        assertEquals(listOf(request.id to true), answers)
        compose.onNodeWithText("Remove").performClick()
        assertTrue(removed.isEmpty())
        capture("android-node-removal-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithText("Remove").performClick()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        assertEquals(listOf("d".repeat(64)), removed)
        compose.onNodeWithText("Close room and delete everything").performScrollTo().performClick()
        assertEquals(0, closes)
        capture("android-node-close-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, closes)
        compose.onNodeWithText("Close room and delete everything").performScrollTo().performClick()
        compose.onNodeWithText("Close and delete").performClick()
        assertEquals(1, closes)
    }
}
