package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File

class CallIdentityUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun profileIdentityAndExplicitAudioHandoffAppearInPrivateCall() {
        val self = "11".repeat(32); val other = "22".repeat(32); val device = "33".repeat(32)
        var handoffs = 0
        var state by mutableStateOf(RoomState(roomId = "44".repeat(32), name = "Alex and Rowan", selfParticipant = self,
            selfDevice = device, relaysUp = 2, relaysTotal = 2, privateConversation = true, profilesEnabled = true, listeningHere = false,
            profiles = mapOf(other to PublicProfile("Rowan", null, 1, "55".repeat(32))),
            tiles = listOf(ParticipantTile(self, true, 2, emptyList(), null, false), ParticipantTile(other, false, 1, listOf(TileTrack("66".repeat(32), "remote-camera", Roles.CAMERA)), "66".repeat(32), false))))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                    modifier = Modifier.fillMaxSize(), chat = { Text("Private chat") },
                    onListenHere = { handoffs++; state = state.copy(listeningHere = true) })
            } } }
            ui.onNodeWithText("Call").performClick()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Rowan"))
            ui.onNodeWithText("Rowan").assertIsDisplayed()
            ui.onNodeWithText(shortNpub(other)).assertIsDisplayed()
            ui.onNodeWithText("Connecting video…").assertIsDisplayed()
            ui.onNodeWithText("Rowan").performClick()
            ui.onNodeWithText(npubOf(other)).assertIsDisplayed()
            ui.onNodeWithText("Done").performClick()
            ui.mainClock.advanceTimeBy(500)
            ui.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir("ui-proof"), "private-call-identity.png")
                .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            ui.onNodeWithText("Listen here").performClick()
            ui.runOnIdle { assertEquals(1, handoffs) }
            ui.onNodeWithText("Call audio is on another of your devices").assertDoesNotExist()
        }
    }
    @Test fun leaveCallIsReachableFromCallAndChatWithoutLeavingTheRoom() {
        var left = 0; var joined = 0; var roomLeft = 0
        // On the call and declared, with somebody else's device on it too, so
        // leaving offers to rejoin rather than to start a fresh one. "On the
        // call" is membership now, never "the engine is running" - see
        // ui/room/CallStance.kt.
        var state by mutableStateOf(
            RoomState(
                roomId = "77".repeat(32), selfParticipant = "11".repeat(32), name = "Call controls",
                micOn = true, onCall = true, callOtherDevices = 1,
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, { roomLeft++ },
                    modifier = Modifier.fillMaxSize(), chat = { Text("Chat is still here") },
                    onLeaveCall = { left++; state = state.copy(onCall = false, mediaRunning = false, micOn = false) },
                    onJoinCall = { joined++; state = state.copy(onCall = true, mediaRunning = true) })
            } } }
            ui.onNodeWithText("Leave call").assertIsDisplayed()
            ui.onNodeWithText("Call", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Leave call").assertIsDisplayed().performClick()
            ui.onNodeWithText("Chat is still here").assertIsDisplayed()
            ui.onNodeWithText("Leave call").assertDoesNotExist()
            ui.onNodeWithText("Call", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Join call").assertIsDisplayed().performClick()
            ui.onNodeWithText("Leave call").assertIsDisplayed()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir("ui-proof"), "leave-call-visible.png")
                .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            ui.onAllNodesWithText("Chat").onFirst().performClick()
            ui.onNodeWithText("Leave call").assertIsDisplayed().performClick()
            ui.runOnIdle { assertEquals(2, left); assertEquals(1, joined); assertEquals(0, roomLeft) }
        }
    }

}
