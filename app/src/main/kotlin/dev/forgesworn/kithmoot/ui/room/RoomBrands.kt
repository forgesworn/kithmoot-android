package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.ui.RoomState
import dev.forgesworn.kithmoot.ui.StartState

private fun canShowProjectBrand(room: RoomState, account: StartState): Boolean =
    !account.anonymousMode && !room.anonymous && !room.quiet && !room.privateConversation && !room.conferenceEnded &&
        account.account?.pubkey == room.selfParticipant && room.selfParticipant.isNotEmpty()

/** Use the visible account's verified directory, including a borrowed account
 * beside a call. A conversation cannot supply the call's originating context. */
internal fun currentRoomProjectBrand(room: RoomState, account: StartState): ProjectRoomBrand? {
    if (!canShowProjectBrand(room, account)) return null
    return if (room.onCall && room.callProjectContextCaptured)
        capturedCallProjectBrand(room.roomId, account.projects, room.callProjectContext)
    else projectRoomBrand(room.roomId, account.projects, room.projectContext)
}

/** Capture even an absent context once. Later sign-in or new project
 * membership must not silently attach another project to an existing call. */
internal fun captureCallProjectOrigin(room: RoomState, account: StartState): RoomState {
    if (room.callProjectContextCaptured) return room
    val project = if (canShowProjectBrand(room, account))
        projectRoomBrand(room.roomId, account.projects, room.projectContext)?.reference else null
    return room.copy(callProjectContext = project, callProjectContextCaptured = true)
}

/** Only a link already held in this account's joined directory may show a
 * project before admission. This lookup never fetches an image or joins a room. */
internal fun currentInvitationProjectBrand(link: String, account: StartState,
    selected: dev.forgesworn.kithmoot.protocol.ProjectReference? = null): ProjectRoomBrand? {
    if (account.account == null || account.anonymousMode) return null
    val invitation = runCatching { dev.forgesworn.kithmoot.protocol.decodeInvitationUrl(link) }.getOrNull() ?: return null
    if (invitation.policy?.quiet == true || dev.forgesworn.kithmoot.session.isDmPolicy(invitation.policy)) return null
    return projectInvitationBrand(link, account.projects, selected = selected)
}

internal fun projectShortcutLogo(room: ProjectRoomChoice, reference: dev.forgesworn.kithmoot.protocol.ProjectReference,
    account: StartState): dev.forgesworn.kithmoot.protocol.LogoImage? {
    val brand = currentInvitationProjectBrand(room.link, account, reference)?.takeIf { it.room == room.room } ?: return null
    val saved = account.savedRooms.find { it.id == room.room }
    if (saved != null && (saved.account != account.account?.pubkey || saved.anonymous || saved.quiet ||
            saved.ended || saved.privatePeer != null)) return null
    return saved?.logo ?: brand.image
}
