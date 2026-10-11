package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.ui.AccountView
import dev.forgesworn.kithmoot.ui.RoomState
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class RoomBrandsTest {
    private val owner = LocalSigner(ByteArray(32) { 1 }).pubkey
    private val roomId = "ab".repeat(32)
    private fun image(): LogoImage {
        val file = listOf("../protocol/src/test/resources/project-logo-web-v1.json", "protocol/src/test/resources/project-logo-web-v1.json")
            .map(::File).first { it.isFile }
        val event = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray.first().jsonObject
            .getValue("event").jsonObject
        return requireNotNull(LogoImages.read(Json.parseToJsonElement(event.getValue("content").jsonPrimitive.content).jsonObject["image"]))
    }
    private fun project(id: Int): SharedProject {
        val ref = ProjectReference(owner, id.toString(16).padStart(64, '0'))
        val definition = buildJsonObject {
            put("name", "Project $id"); put("archived", false); put("authorityRevision", 1)
            put("members", JsonArray(listOf(buildJsonObject { put("pubkey", owner); put("kind", "person"); put("epoch", 1) })))
            put("rooms", JsonArray(listOf(ProjectRoomChoice(roomId, "Workshop",
                encodeInvitationUrl("https://fixture.invalid/j/", createRoomInvitation(true).invitation, emptyList())).toJson())))
        }
        return SharedProject(ref, listOf("cd".repeat(32)), 1, definition, Projects.authority(ref, definition), true, false, false, logo = image())
    }
    private fun account(vararg projects: SharedProject) = StartState(
        account = AccountView(owner, npubOf(owner), "Owner", "local"),
        projects = ProjectAccountSnapshot(projects.toList(), ready = true),
    )
    private fun room() = RoomState(roomId = roomId, name = "Workshop", selfParticipant = owner)

    @Test fun room_brand_requires_the_visible_account_to_match_its_admitted_identity() {
        val p = project(1); val account = account(p)
        assertEquals(p.reference, currentRoomProjectBrand(room(), account)?.reference)
        assertNull(currentRoomProjectBrand(room(), account.copy(account = null)))
        val other = LocalSigner(ByteArray(32) { 2 }).pubkey
        assertNull(currentRoomProjectBrand(room(), account.copy(account = AccountView(other, npubOf(other), "Other", "local"))))
        assertNull(currentRoomProjectBrand(room().copy(selfParticipant = ""), account))
        assertNull(currentRoomProjectBrand(room(), account.copy(anonymousMode = true)))
    }

    @Test fun private_quiet_anonymous_and_ended_rooms_do_not_inherit_project_identity() {
        val account = account(project(1)); val base = room()
        for (hidden in listOf(base.copy(privateConversation = true), base.copy(quiet = true),
            base.copy(anonymous = true), base.copy(conferenceEnded = true))) {
            assertNull(currentRoomProjectBrand(hidden, account))
            assertNull(captureCallProjectOrigin(hidden, account).callProjectContext)
        }
    }

    @Test fun a_call_origin_is_captured_once_and_survives_different_conversation_contexts() {
        val first = project(1); val second = project(2); val account = account(first, second)
        val captured = captureCallProjectOrigin(room().copy(projectContext = first.reference), account).copy(onCall = true)
        assertEquals(first.reference, captured.callProjectContext)
        val navigated = captured.copy(projectContext = second.reference)
        assertEquals(first.reference, currentRoomProjectBrand(navigated, account)?.reference)
        assertEquals(first.reference, captureCallProjectOrigin(navigated, account).callProjectContext)
        assertNull(currentRoomProjectBrand(navigated, account.copy(projects = ProjectAccountSnapshot(listOf(second), ready = true))))
    }

    @Test fun later_account_or_directory_recovery_does_not_invent_a_context_for_an_existing_call() {
        val p = project(1); val account = account(p)
        val captured = captureCallProjectOrigin(room(), account.copy(projects = ProjectAccountSnapshot())).copy(onCall = true)
        assertTrue(captured.callProjectContextCaptured); assertNull(captured.callProjectContext)
        assertNull(currentRoomProjectBrand(captured, account))
        assertNull(captureCallProjectOrigin(captured, account).callProjectContext)
        val afterLeave = captured.copy(onCall = false, callProjectContext = null, callProjectContextCaptured = false)
        assertEquals(p.reference, captureCallProjectOrigin(afterLeave, account).callProjectContext)
    }

    @Test fun displaying_inheritance_does_not_rewrite_room_metadata_or_editing_authority() {
        val p = project(1); val actual = room()
        val displayed = actual.copy(projectBrand = currentRoomProjectBrand(actual, account(p)))
        assertEquals("Project 1", displayed.projectBrand?.projectName)
        assertEquals(actual.logo, displayed.logo)
        assertEquals(actual.selfParticipant, displayed.selfParticipant)
        assertEquals(actual.roomId, displayed.roomId)
        assertNull(actual.projectBrand); assertNull(actual.projectContext)
    }

    @Test fun an_invitation_brand_needs_an_existing_exact_account_context_and_respects_anonymous_mode() {
        val p = project(1); val account = account(p); val link = p.roomChoices().single().link
        assertEquals(p.reference, currentInvitationProjectBrand(link, account)?.reference)
        assertNull(currentInvitationProjectBrand("https://fixture.invalid/j/#unknown", account))
        assertNull(currentInvitationProjectBrand(link, account.copy(account = null)))
        assertNull(currentInvitationProjectBrand(link, account.copy(anonymousMode = true)))
        assertNull(currentInvitationProjectBrand(link, account.copy(projects = ProjectAccountSnapshot())))
    }

    @Test fun project_shortcuts_use_the_selected_project_and_admitted_room_override() {
        val first = project(1); val second = project(2); val account = account(first, second)
        val choice = first.roomChoices().single()
        assertSame(first.logo, projectShortcutLogo(choice, first.reference, account))
        val override = image()
        val saved = SavedRoomSummary(roomId, "Workshop", false, 10, account = owner, logo = override)
        assertSame(override, projectShortcutLogo(choice, first.reference, account.copy(savedRooms = listOf(saved))))
        for (hidden in listOf(saved.copy(anonymous = true), saved.copy(quiet = true), saved.copy(ended = true),
            saved.copy(privatePeer = "ef".repeat(32)), saved.copy(account = null), saved.copy(account = "ef".repeat(32)))) {
            assertNull(projectShortcutLogo(choice, first.reference, account.copy(savedRooms = listOf(hidden))))
        }
        assertNull(projectShortcutLogo(choice, first.reference, account.copy(anonymousMode = true)))
        assertNull(projectShortcutLogo(choice, first.reference, account.copy(projects = ProjectAccountSnapshot())))
    }
}
