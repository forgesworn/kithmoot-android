package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.ProjectReference
import dev.forgesworn.kithmoot.protocol.SharedProject
import dev.forgesworn.kithmoot.storage.RoomRecoveryException
import kotlinx.serialization.json.*

data class ProjectRoomChoice(val room: String, val name: String, val link: String) {
    fun toJson() = buildJsonObject { put("room", room); put("name", name); put("link", link) }
}
fun SharedProject.roomChoices(): List<ProjectRoomChoice> = definition?.get("rooms")?.jsonArray?.map {
    val r = it.jsonObject
    ProjectRoomChoice(r.getValue("room").jsonPrimitive.content, r.getValue("name").jsonPrimitive.content, r.getValue("link").jsonPrimitive.content)
}.orEmpty()

/** Rechecked after admission and external signing; a display label is never a room or account identity. */
fun selectedProjectRoom(state: ProjectAccountSnapshot, ref: ProjectReference, room: String, authority: String?): ProjectRoomChoice {
    val selected = state.projects.find { it.key == ref.key }
    if (!state.ready || selected == null || !selected.joined || selected.withdrawn || selected.conflicted || selected.archived ||
        authority == null || selected.authority != authority) throw RoomRecoveryException("This project changed. Review it before opening the room.")
    return selected.roomChoices().find { it.room == room } ?: throw RoomRecoveryException("This room is no longer in the project.")
}

fun checkProjectRoomAdmission(expected: String, admitted: String) {
    if (expected != admitted) throw RoomRecoveryException("This invitation opens a different room. Ask the project owner to correct it.")
}
