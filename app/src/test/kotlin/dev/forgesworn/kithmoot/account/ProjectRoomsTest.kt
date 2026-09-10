package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomRecoveryException
import dev.forgesworn.kithmoot.ui.start.projectDefinition
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ProjectRoomsTest {
    private val owner = LocalSigner(ByteArray(32) { 1 }).pubkey
    private val member = LocalSigner(ByteArray(32) { 2 }).pubkey
    private val room = ProjectRoomChoice("ab".repeat(32), "Build room", encodeInvitationUrl("https://fixture.invalid/j/", createRoomInvitation(true).invitation, emptyList()))
    private val ref = ProjectReference(owner, Projects.id(owner, "project-room-selector"))
    private val definition = projectDefinition(owner, null, "Example", npubOf(member), "", listOf(room), false)
    private val project = SharedProject(ref, listOf("cd".repeat(32)), 1, definition, Projects.authority(ref, definition), true, false, false)

    @Test fun roomSelectionRequiresTheExactProjectAndCurrentEligibility() {
        val snapshot = ProjectAccountSnapshot(listOf(project), ready = true)
        assertEquals(room, selectedProjectRoom(snapshot, ref, room.room, project.authority))
        for (state in listOf(snapshot.copy(ready = false), snapshot.copy(projects = emptyList()),
            snapshot.copy(projects = listOf(project.copy(joined = false))), snapshot.copy(projects = listOf(project.copy(withdrawn = true))),
            snapshot.copy(projects = listOf(project.copy(conflicted = true))),
            snapshot.copy(projects = listOf(project.copy(definition = JsonObject(definition + ("archived" to JsonPrimitive(true)))))))) {
            assertFailsWith<RoomRecoveryException> { selectedProjectRoom(state, ref, room.room, project.authority) }
        }
        assertFailsWith<RoomRecoveryException> { selectedProjectRoom(snapshot, ref, "ef".repeat(32), project.authority) }
        assertFailsWith<RoomRecoveryException> { selectedProjectRoom(snapshot, ref, room.room, "ef".repeat(32)) }
        assertFailsWith<RoomRecoveryException> { checkProjectRoomAdmission(room.room, "ef".repeat(32)) }
        checkProjectRoomAdmission(room.room, room.room)
    }

    @Test fun editorKeepsRemoteInvitationsAndNamesAndRefusesSecretsOrDuplicateRoles() {
        val named = JsonObject(definition + ("members" to JsonArray(definition.getValue("members").jsonArray.map { raw ->
            if (raw.jsonObject["pubkey"] == JsonPrimitive(member)) JsonObject(raw.jsonObject + ("name" to JsonPrimitive("Robin"))) else raw
        })))
        val edited = projectDefinition(owner, project.copy(definition = named), "Renamed", npubOf(member), "", project.roomChoices(), true)
        assertEquals(named.getValue("members").jsonArray.last(), edited.getValue("members").jsonArray.last())
        assertEquals(definition.getValue("rooms"), edited.getValue("rooms"))
        assertEquals(JsonPrimitive(true), edited["archived"])
        assertFailsWith<IllegalArgumentException> { projectDefinition(owner, null, "Project", npubOf(member), npubOf(member), emptyList(), false) }
        assertFailsWith<IllegalArgumentException> { projectDefinition(owner, null, "Project", Bech32.encode("nsec", ByteArray(32) { 2 }), "", emptyList(), false) }
    }
}
