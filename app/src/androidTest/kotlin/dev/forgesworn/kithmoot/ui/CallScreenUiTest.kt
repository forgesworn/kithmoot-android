package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.test.espresso.Espresso
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.node.NodeHostedRoomView
import dev.forgesworn.kithmoot.protocol.NodeJoinRequest
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Signal-style call view: who fills the screen, what is at hand, what is tucked away. */
class CallScreenUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val self = "11".repeat(32)
    private fun person(id: String, name: String, self: Boolean = false) =
        ParticipantTile(id, self, 1, listOf(TileTrack(id.take(62) + "dd", "camera-$id", Roles.CAMERA)), id.take(62) + "dd", false, name = name)

    private fun onCall(initial: RoomState, onLeaveCall: () -> Unit = {}, fontScale: Float? = null, checks: () -> Unit) {
        var state by mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KithMootTheme {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                            RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                                modifier = Modifier.fillMaxSize(), chat = { Text("Chat here") },
                                onLoudspeaker = { on -> state = state.copy(loudspeakerOn = on) },
                                onLeaveCall = { onLeaveCall(); state = state.copy(onCall = false) })
                        }
                    }
                }
            }
            // On the call and reading the chat; Return goes back to the call.
            ui.onNodeWithText("Return").performClick()
            checks()
        }
    }

    @Test fun audio_location_and_invitation_notices_do_not_overlap_at_large_text_size() {
        onCall(RoomState(roomId = "41".repeat(32), selfParticipant = self, onCall = true, listeningHere = false,
            joinUrl = "https://kithmoot.example/j/#fixture", tiles = listOf(person(self, "Alex", self = true))),
            fontScale = 2f) {
            val audio = ui.onNodeWithText("Call audio is on another of your devices").assertIsDisplayed().getUnclippedBoundsInRoot()
            val listen = ui.onNodeWithText("Listen here").assertIsDisplayed().getUnclippedBoundsInRoot()
            val alone = ui.onNodeWithText("Nobody else is here yet").assertExists().getUnclippedBoundsInRoot()
            assertTrue("Audio notice and its action must finish above the invitation", maxOf(audio.bottom, listen.bottom) <= alone.top)
            ui.onNodeWithText("Send link").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun one_other_person_fills_the_call_with_four_controls_and_more() {
        var left = 0
        onCall(
            RoomState(roomId = "44".repeat(32), selfParticipant = self, onCall = true, cameraOn = true,
                tiles = listOf(person(self, "Alex", self = true), person("22".repeat(32), "Rowan"))),
            onLeaveCall = { left++ },
        ) {
        ui.onNodeWithText("Rowan").assertIsDisplayed()
        ui.onNodeWithText("Alex (you)").assertIsDisplayed()
        // The bar's words are what pressing does; the microphone keeps its name for TalkBack.
        for (label in listOf("Unmute", "Camera", "Chat", "More", "Leave")) ui.onNodeWithText(label).assertIsDisplayed()
        ui.onNodeWithContentDescription("Microphone").assertIsDisplayed()
        ui.onAllNodesWithContentDescription("Leave call").assertCountEquals(1)
        // Everything else waits in More, and Flip camera with the effects.
        ui.onNodeWithText("Flip camera").assertDoesNotExist()
        ui.onNodeWithText("More").performClick()
        ui.onNodeWithText("Share screen").assertIsDisplayed()
        ui.onNodeWithText("Invite by QR").assertDoesNotExist()
        ui.onNodeWithText("Effects…").performClick()
        ui.onNodeWithText("Effects").assertIsDisplayed()
        ui.onNodeWithText("Flip camera").assertIsDisplayed()
        Espresso.pressBack()
        ui.onNodeWithText("More").performClick()
        ui.onNodeWithText("Layout").performClick()
        ui.onNodeWithText("Hide my picture").performClick()
        Espresso.pressBack()
        ui.onNodeWithText("Alex (you)").assertDoesNotExist()
        ui.onNodeWithText("More").performClick()
        ui.onNodeWithText("Layout").performClick()
        ui.onNodeWithText("Hide my picture").performClick()
        Espresso.pressBack()
        ui.onNodeWithText("Alex (you)").assertIsDisplayed()
        ui.onNodeWithContentDescription("Leave call").performClick()
        ui.onNodeWithText("Chat here").assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, left) }
        }
    }

    @Test fun loudspeaker_switch_changes_the_call_route_choice() {
        onCall(RoomState(roomId = "47".repeat(32), selfParticipant = self, onCall = true,
            tiles = listOf(person(self, "Alex", self = true), person("22".repeat(32), "Rowan")))) {
            ui.onNodeWithText("More").performClick()
            ui.onNodeWithText("Loudspeaker").assertIsOn().performClick()
            ui.onNodeWithText("Loudspeaker").assertIsOff().performClick()
            ui.onNodeWithText("Loudspeaker").assertIsOn()
        }
    }

    @Test fun whoever_is_speaking_is_marked_in_words_as_well_as_colour() {
        val other = "22".repeat(32)
        onCall(
            RoomState(roomId = "45".repeat(32), selfParticipant = self, onCall = true, speaking = setOf(other),
                tiles = listOf(person(self, "Alex", self = true), person(other, "Rowan"))),
        ) {
            ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Speaking")).assertCountEquals(1)
        }
    }

    @Test fun three_others_share_the_screen_equally() {
        val others = listOf("Rowan", "Sam", "Kit").mapIndexed { index, name -> person("${index + 3}".repeat(64), name) }
        onCall(RoomState(roomId = "46".repeat(32), selfParticipant = self, onCall = true,
            tiles = listOf(person(self, "Alex", self = true)) + others)) {
        val sizes = listOf("Rowan", "Sam", "Kit").map { name ->
            ui.onNodeWithText(name).assertIsDisplayed()
            ui.onNode(hasClickAction() and hasAnyDescendant(hasText(name)) and !hasText(name)).fetchSemanticsNode().size
        }
        android.util.Log.i("KithMootUiTest", "Equal grid tile sizes: $sizes")
        // Equal weights share an odd physical-pixel remainder between cells.
        assertTrue("grid widths must match within one physical pixel: $sizes", sizes.maxOf { it.width } - sizes.minOf { it.width } <= 1)
        assertTrue("grid heights must match within one physical pixel: $sizes", sizes.maxOf { it.height } - sizes.minOf { it.height } <= 1)
        // Gallery and Speaker are for large calls only.
        ui.onNodeWithText("More").performClick()
        ui.onNodeWithText("Layout").performClick()
        ui.onNodeWithText("Gallery").assertDoesNotExist()
        }
    }
    @Test fun landscape_gives_video_the_height_and_keeps_every_control_at_the_side() {
        val levels = kotlinx.coroutines.flow.MutableStateFlow(dev.forgesworn.kithmoot.media.CallAudioActivity(0.1,
            listOf(-0.1f, 0.2f, -0.05f, 0.15f)))
        var state by mutableStateOf(RoomState(roomId = "48".repeat(32), selfParticipant = self, onCall = true,
            cameraOn = true, micOn = true, tiles = listOf(person(self, "Alex", self = true), person("22".repeat(32), "Rowan"))))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { KithMootTheme {
                    RoomScreen(state, emptyMap(), null, { state = state.copy(micMuted = !state.micMuted) }, {}, {}, {}, {}, {}, {}, {},
                        modifier = Modifier.fillMaxSize(), chat = { Text("Chat here") }, audioActivity = levels)
                } }
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            ui.waitUntil(10_000) {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            ui.onNodeWithText("Return").performClick()
            val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
            val stage = ui.onNodeWithTag("call-video-stage").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Video must use at least 80% of landscape height: $stage / $root", stage.height >= root.height * 0.8f)
            for (label in listOf("Mute", "Camera", "Chat", "More", "Leave")) {
                val control = ui.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue("$label belongs beside the video", control.left >= stage.right)
            }
            for (next in listOf(0.0, 0.1, null, 0.2)) {
                ui.runOnIdle { levels.value = dev.forgesworn.kithmoot.media.CallAudioActivity(next) }
                assertEquals(stage, ui.onNodeWithTag("call-video-stage").fetchSemanticsNode().boundsInRoot)
            }
            ui.onNodeWithText("Mute").performClick()
            ui.onNodeWithText("Unmute").assertIsDisplayed()
            ui.onNodeWithText("More").performClick()
            ui.onNodeWithText("Effects…").assertIsDisplayed()
            Espresso.pressBack()
            val waiting = NodeJoinRequest("a".repeat(64), "b".repeat(64), "c".repeat(64),
                JsonObject(emptyMap()), "Rowan's phone", 1900000600)
            ui.runOnIdle { state = state.copy(nodeHosted = NodeHostedRoomView("node.example", true, requests = listOf(waiting))) }
            ui.onNodeWithText("Rowan's phone wants to join").assertIsDisplayed()
            ui.onNodeWithText("Admit").performScrollTo().assertIsDisplayed()
            ui.runOnIdle { state = state.copy(nodeHosted = null) }
            val bitmap = ui.onRoot().captureToImage()
            val file = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "call-landscape.png")
            file.outputStream().use { bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(10_000) {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            for (label in listOf("Unmute", "Camera", "Chat", "More", "Leave")) ui.onNodeWithText(label).assertIsDisplayed()
            ui.onNodeWithText("Chat").performClick()
            ui.onNodeWithText("Chat here").assertIsDisplayed()
        }
    }

}
