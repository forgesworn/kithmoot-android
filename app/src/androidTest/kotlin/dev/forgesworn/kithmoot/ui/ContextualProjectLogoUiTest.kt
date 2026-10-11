package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Drawn Compose surfaces; synthetic media state does not qualify a physical call. */
class ContextualProjectLogoUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = LocalSigner(ByteArray(32) { 1 }).pubkey
    private val roomId = "ab".repeat(32)
    private fun project(id: Int): SharedProject {
        val ref = ProjectReference(owner, id.toString(16).padStart(64, '0'))
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open("logo-images-v1.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject.getValue("vectors").jsonArray
            .mapNotNull { LogoImages.read(it.jsonObject["payload"]) }.first()
        val definition = buildJsonObject {
            put("name", "Project $id"); put("archived", false); put("authorityRevision", 1)
            put("members", JsonArray(listOf(buildJsonObject { put("pubkey", owner); put("kind", "person"); put("epoch", 1) })))
            put("rooms", JsonArray(listOf(ProjectRoomChoice(roomId, "Workshop",
                encodeInvitationUrl("https://fixture.invalid/j/", createRoomInvitation(true).invitation, emptyList())).toJson())))
        }
        return SharedProject(ref, listOf("cd".repeat(32)), 1, definition, Projects.authority(ref, definition), true, false, false, logo = image)
    }
    private fun account(vararg projects: SharedProject) = StartState(account = AccountView(owner, npubOf(owner), "Owner", "local"),
        projects = ProjectAccountSnapshot(projects.toList(), ready = true))
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = instrumentation.targetContext.getExternalFilesDir("ui-proof")!!; directory.mkdirs()
        try { File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun callOrigin(dark: Boolean) {
        val first = project(1); val second = project(2)
        val directory = mutableStateOf(account(first, second))
        val call = captureCallProjectOrigin(RoomState(roomId = roomId, name = "Workshop", selfParticipant = owner,
            projectContext = first.reference), directory.value).copy(onCall = true, projectContext = second.reference)
        var returned = 0; var left = 0; var toggled = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                KithMootTheme(darkTheme = dark) {
                    Column {
                        CallDock(call.copy(projectBrand = currentRoomProjectBrand(call, directory.value)),
                            onToggleMic = { toggled++ }, onBack = { returned++ }, onLeave = { left++ })
                        Text("Reading another room in Project 2")
                    }
                }
            }
        }
        compose.onNodeWithText("Project 1").assertIsDisplayed()
        compose.onNodeWithText("Reading another room in Project 2").assertIsDisplayed()
        compose.onNodeWithText("Mic off").assertIsDisplayed().performClick()
        compose.onNodeWithText("Back to the call").assertIsDisplayed().performClick()
        compose.onNodeWithText("Leave call").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, returned); assertEquals(1, left); assertEquals(1, toggled) }
        screenshot("contextual-call-${if (dark) "dark" else "light"}-large.png")
        compose.runOnIdle { directory.value = account(second) }
        compose.onAllNodesWithText("Project 1").assertCountEquals(0)
        compose.onAllNodesWithText("Project 2", substring = false).assertCountEquals(0)
        compose.onNodeWithText("Back to the call").assertIsDisplayed()
    }
    @Test fun call_origin_and_controls_remain_visible_with_large_text_in_light_theme() = callOrigin(false)
    @Test fun call_origin_and_controls_remain_visible_with_large_text_in_dark_theme() = callOrigin(true)

    private fun roomHeader(dark: Boolean) {
        val project = project(1); val directory = mutableStateOf(account(project))
        val actual = RoomState(roomId = roomId, name = "Workshop", selfParticipant = owner, relaysUp = 1)
        compose.setContent {
            KithMootTheme(darkTheme = dark) {
                RoomScreen(actual.copy(projectBrand = currentRoomProjectBrand(actual, directory.value)), emptyMap(), null,
                    onToggleMic = {}, onToggleAgentsMayHear = {}, onToggleCamera = {}, onSwitchCamera = {},
                    onToggleScreenShare = {}, onAddDevice = {}, onRotateInvitation = {}, onLeave = {}, chat = { Text("Conversation") })
            }
        }
        compose.onNodeWithText("Workshop").assertIsDisplayed()
        compose.onNodeWithText("Project 1").assertIsDisplayed()
        screenshot("contextual-room-${if (dark) "dark" else "light"}.png")
        compose.runOnIdle { directory.value = account(project.copy(withdrawn = true)) }
        compose.onAllNodesWithText("Project 1").assertCountEquals(0)
        compose.onNodeWithText("Workshop").assertIsDisplayed()
        compose.onNodeWithText("Conversation").assertIsDisplayed()
    }
    @Test fun room_header_updates_project_membership_in_light_theme() = roomHeader(false)
    @Test fun room_header_updates_project_membership_in_dark_theme() = roomHeader(true)

    @Test fun admission_branding_is_local_and_withdrawal_does_not_change_a_pending_request() {
        val project = project(1); val directory = mutableStateOf(account(project))
        val link = project.roomChoices().single().link
        val gate = GuestAdmissionGate(); gate.prepare(link, "Workshop", "Casey")
        var request: GuestAdmissionAttempt? = null
        compose.setContent {
            val view by gate.view.collectAsState()
            KithMootTheme(darkTheme = true) {
                view?.let { current -> GuestAdmissionDialog(current, false, devicesAvailable = false,
                    onName = gate::editName, onRequest = { request = gate.request() }, onCancel = gate::cancel,
                    onClose = gate::clear, onRetry = { gate.retry() },
                    projectBrand = currentInvitationProjectBrand(link, directory.value)) }
            }
        }
        compose.onNodeWithText("Project 1").assertIsDisplayed()
        compose.onNodeWithText("This project comes from your joined project list. Its logo is a label, not proof of access.").assertIsDisplayed()
        screenshot("contextual-admission-known-project.png")
        compose.onNodeWithText("Request to join").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(request); assertEquals("Casey", request!!.name)
            assertEquals(GuestAdmissionPhase.SENDING, gate.view.value!!.phase)
            directory.value = account(project.copy(withdrawn = true))
        }
        compose.onAllNodesWithText("Project 1").assertCountEquals(0)
        compose.onNodeWithText("Cancel request").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(gate.isCurrent(request!!)); assertEquals("Casey", gate.view.value!!.name) }
        compose.onNodeWithText("Cancel request").performClick()
        compose.onNodeWithText("Your request was cancelled").assertIsDisplayed()
        compose.onNodeWithText("Review and retry").performScrollTo().performClick()
        compose.onNodeWithText("Casey").assertIsDisplayed()
    }
}
