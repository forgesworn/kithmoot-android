package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.start.mergeRooms

class ProjectBrandsTest {
    private val room = "ab".repeat(32)
    private val otherRoom = "ef".repeat(32)
    private val owner = LocalSigner(ByteArray(32) { 1 }).pubkey
    private val link = encodeInvitationUrl("https://kithmoot.app/j/", createRoomInvitation(true).invitation, emptyList())
    private fun image(): LogoImage {
        val file = listOf("../protocol/src/test/resources/project-logo-web-v1.json", "protocol/src/test/resources/project-logo-web-v1.json")
            .map(::File).first { it.isFile }
        val event = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray.first().jsonObject
            .getValue("event").jsonObject
        return requireNotNull(LogoImages.read(Json.parseToJsonElement(event.getValue("content").jsonPrimitive.content).jsonObject["image"]))
    }
    private fun project(id: Int, target: String = room, invitation: String = link): SharedProject {
        val ref = ProjectReference(owner, id.toString(16).padStart(64, '0'))
        val definition = buildJsonObject {
            put("name", "Project $id"); put("archived", false); put("authorityRevision", 1)
            put("members", JsonArray(listOf(buildJsonObject {
                put("pubkey", ref.owner); put("kind", "person"); put("epoch", 1)
            })))
            put("rooms", JsonArray(listOf(ProjectRoomChoice(target, "Workshop", invitation).toJson())))
        }
        return SharedProject(ref, listOf("cd".repeat(32)), 1, definition, Projects.authority(ref, definition),
            joined = true, withdrawn = false, conflicted = false, logo = image())
    }
    private fun state(vararg projects: SharedProject) = ProjectAccountSnapshot(projects.toList(), ready = true)

    @Test fun a_sole_joined_context_supplies_artwork_and_removal_restores_inheritance() {
        val p = project(1); val directory = state(p)
        assertEquals(p.logo, inheritedRoomLogo(room, null, directory))
        assertEquals(p.reference, projectRoomBrand(room, directory)?.reference)
        assertNull(inheritedRoomLogo(otherRoom, null, directory))
        assertEquals(directory, state(p), "Displaying a brand must not create membership or change the directory")
    }

    @Test fun unavailable_and_unjoined_projects_cannot_supply_a_room_brand() {
        val p = project(1)
        assertNull(projectRoomBrand(room, state(p).copy(ready = false)))
        val archived = p.copy(definition = JsonObject(p.definition!! + ("archived" to JsonPrimitive(true))))
        for (hidden in listOf(p.copy(joined = false), p.copy(withdrawn = true), p.copy(conflicted = true),
            p.copy(definition = null), archived)) {
            assertNull(projectRoomBrand(room, state(hidden)))
            assertNull(projectInvitationBrand(link, state(hidden)))
        }
        val withheld = projectRoomBrand(room, state(p.copy(logoConflicted = true)))!!
        assertEquals("Project 1", withheld.projectName); assertEquals("Workshop", withheld.roomName); assertNull(withheld.image)
    }

    @Test fun shared_rooms_require_selection_when_multiple_joined_contexts_exist() {
        val first = project(1); val second = project(2)
        assertNull(projectRoomBrand(room, state(first, second)))
        assertEquals(second.reference, projectRoomBrand(room, state(first, second), second.reference)?.reference)
        assertNull(projectRoomBrand(room, state(first.copy(logo = null), second)))
        assertEquals(first.reference, projectRoomBrand(room, state(first, project(2, otherRoom)), second.reference)?.reference)
    }

    @Test fun an_admitted_room_override_wins_and_a_removed_override_uses_its_context() {
        val p = project(1); val artwork = image()
        assertEquals(artwork, inheritedRoomLogo(room, artwork, ProjectAccountSnapshot()))
        assertEquals(p.logo, inheritedRoomLogo(room, null, state(p)))
        assertNull(inheritedRoomLogo(room, null, state(p.copy(logo = null))))
    }

    @Test fun the_home_index_counts_contexts_without_artwork_and_withholds_ambiguous_rooms() {
        val first = project(1); val second = project(2)
        assertEquals(first.reference, soleProjectRoomBrands(state(first)).getValue(room).reference)
        assertTrue(soleProjectRoomBrands(state(first, second.copy(logo = null))).isEmpty())
        assertTrue(soleProjectRoomBrands(state(first, second.copy(logoConflicted = true))).isEmpty())
        assertEquals(first.reference, soleProjectRoomBrands(state(first, second.copy(withdrawn = true))).getValue(room).reference)
        assertTrue(soleProjectRoomBrands(state(first).copy(ready = false)).isEmpty())
    }

    @Test fun saved_rows_require_the_same_account_and_suppress_private_quiet_anonymous_and_ended_artwork() {
        val p = project(1); val saved = SavedRoomSummary(room, "Workshop", false, 10, account = owner)
        fun row(value: SavedRoomSummary, account: String? = owner, signedIn: Boolean = true) =
            mergeRooms(listOf(value), emptyList(), signedIn, account, state(p)).single()
        assertEquals(p.logo, row(saved).logo)
        for (hidden in listOf(saved.copy(anonymous = true), saved.copy(quiet = true), saved.copy(ended = true),
            saved.copy(privatePeer = "ef".repeat(32)), saved.copy(account = null))) assertNull(row(hidden).logo)
        assertNull(row(saved, account = "ef".repeat(32)).logo)
        assertNull(row(saved, signedIn = false).logo)
        val override = image()
        assertSame(override, row(saved.copy(logo = override)).logo)
        assertEquals(p.logo, row(saved.copy(logo = null)).logo)
        assertEquals(10L, row(saved).openedAt)
    }

    @Test fun account_bookmarks_inherit_only_for_usable_regular_links_without_changing_activity() {
        val p = project(1)
        fun row(link: String) = mergeRooms(emptyList(), listOf(AccountRoom(room, link, "Workshop", 10)), true, owner, state(p)).single()
        assertEquals(p.logo, row(link).logo)
        assertNull(row("broken invitation").logo)
        assertNull(row(encodeJoinUrl("https://fixture.invalid/j/", ByteArray(32) { 2 }, emptyList(),
            dev.forgesworn.kithmoot.session.dmPolicy(owner, LocalSigner(ByteArray(32) { 3 }).pubkey))).logo)
        assertNull(row(encodeJoinUrl("https://fixture.invalid/j/", ByteArray(32) { 2 }, emptyList(),
            RoomPolicy(KindredTier.OPEN, members = listOf(owner, LocalSigner(ByteArray(32) { 3 }).pubkey,
                LocalSigner(ByteArray(32) { 4 }).pubkey), quiet = true))).logo)
        assertEquals(10L, row(link).openedAt)
    }

    @Test fun a_captured_call_never_borrows_a_different_project_after_navigation_or_withdrawal() {
        val first = project(1); val second = project(2)
        assertEquals(first.reference, capturedCallProjectBrand(room, state(second, first), first.reference)?.reference)
        assertNull(capturedCallProjectBrand(room, state(second, first.copy(withdrawn = true)), first.reference))
        assertNull(capturedCallProjectBrand(room, state(second), first.reference))
        assertNull(capturedCallProjectBrand(room, state(first), null))
    }

    @Test fun cold_invitation_preview_requires_an_exact_known_fragment() {
        val p = project(1); val directory = state(p)
        val fragment = link.substringAfter('#')
        assertEquals(p.reference, projectInvitationBrand("https://another-client.example/j/#$fragment", directory)?.reference)
        for (unknown in listOf("https://kithmoot.app/j/#unknown", "https://kithmoot.app/j/", "broken link")) {
            assertNull(projectInvitationBrand(unknown, directory))
        }
        val differentlyEncoded = "%" + fragment.first().code.toString(16) + fragment.drop(1)
        assertNull(projectInvitationBrand("https://kithmoot.app/j/#$differentlyEncoded", directory))
    }

    @Test fun ambiguous_or_mismatching_invitation_room_ids_do_not_preview_artwork() {
        val first = project(1); val second = project(2)
        assertNull(projectInvitationBrand(link, state(first, second)))
        assertEquals(second.reference, projectInvitationBrand(link, state(first, second), selected = second.reference)?.reference)
        assertNull(projectInvitationBrand(link, state(first, project(2, otherRoom)), selected = first.reference))
        assertNull(projectInvitationBrand(link, state(first, project(2, otherRoom)), room, first.reference))
    }

    @Test fun authenticated_resolution_allows_a_refreshed_invitation_only_in_its_existing_project() {
        val p = project(1); val refreshed = "https://kithmoot.app/j/#refreshed-invitation"
        assertNull(projectInvitationBrand(refreshed, state(p)))
        assertEquals(p.reference, projectInvitationBrand(refreshed, state(p), room)?.reference)
        assertNull(projectInvitationBrand(refreshed, state(p), otherRoom))
        assertNull(projectInvitationBrand(link, state(p), otherRoom))
        assertNull(projectInvitationBrand(refreshed, state(p.copy(joined = false)), room))
    }
}
