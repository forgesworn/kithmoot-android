package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.LogoImage
import dev.forgesworn.kithmoot.protocol.ProjectReference
import dev.forgesworn.kithmoot.protocol.SharedProject
import java.net.URI

/** Local display evidence from the verified account directory, never admission authority. */
data class ProjectRoomBrand(
    val reference: ProjectReference,
    val room: String,
    val roomName: String,
    val projectName: String,
    val image: LogoImage?,
)

private fun ProjectAccountSnapshot.brandProjects(): List<SharedProject> = if (!ready) emptyList() else projects.filter {
    it.joined && !it.withdrawn && !it.conflicted && !it.archived && it.definition != null
}

private fun SharedProject.brand(room: ProjectRoomChoice) = ProjectRoomBrand(reference, room.room, room.name,
    requireNotNull(name), logo.takeUnless { logoConflicted })

/** Follow a deliberate project selection, or a sole matching joined project. */
fun projectRoomBrand(room: String, state: ProjectAccountSnapshot, selected: ProjectReference? = null): ProjectRoomBrand? {
    val candidates = state.brandProjects().mapNotNull { project ->
        project.roomChoices().find { it.room == room }?.let { project.brand(it) }
    }
    return candidates.find { it.reference == selected } ?: candidates.singleOrNull()
}

/** A call keeps the project captured on joining, even when navigation changes.
 * Withdrawal/archival clears that brand rather than borrowing another project. */
fun capturedCallProjectBrand(room: String, state: ProjectAccountSnapshot, origin: ProjectReference?): ProjectRoomBrand? {
    if (origin == null) return null
    val project = state.brandProjects().find { it.reference == origin } ?: return null
    return project.roomChoices().find { it.room == room }?.let { project.brand(it) }
}

fun inheritedRoomLogo(room: String, override: LogoImage?, state: ProjectAccountSnapshot,
    selected: ProjectReference? = null): LogoImage? = override ?: projectRoomBrand(room, state, selected)?.image

/** Build once for a rooms list. Missing artwork still counts as a context,
 * so a second project cannot silently inherit ownership from the first. */
fun soleProjectRoomBrands(state: ProjectAccountSnapshot): Map<String, ProjectRoomBrand> = state.brandProjects()
    .flatMap { project -> project.roomChoices().map { project.brand(it) } }
    .groupBy { it.room }.mapNotNull { (room, candidates) -> candidates.singleOrNull()?.let { room to it } }.toMap()

private fun invitationFragment(link: String): String? = runCatching { URI(link).rawFragment }
    .getOrNull()?.takeIf { it.isNotEmpty() }

/** An unknown invitation cannot select a project. Match the exact fragment
 * until authenticated resolution establishes its room ID; neither path joins it. */
fun projectInvitationBrand(link: String, state: ProjectAccountSnapshot, resolvedRoom: String? = null,
    selected: ProjectReference? = null): ProjectRoomBrand? {
    val fragment = invitationFragment(link) ?: return null
    val rooms = state.brandProjects().flatMap { project -> project.roomChoices().map { project to it } }
    val exact = rooms.filter { invitationFragment(it.second.link) == fragment }
    if (resolvedRoom != null && exact.any { it.second.room != resolvedRoom }) return null
    val candidates = if (resolvedRoom == null) exact else rooms.filter { it.second.room == resolvedRoom }
    if (candidates.map { it.second.room }.distinct().size != 1) return null
    val chosen = candidates.find { it.first.reference == selected } ?: candidates.singleOrNull() ?: return null
    return chosen.first.brand(chosen.second)
}
