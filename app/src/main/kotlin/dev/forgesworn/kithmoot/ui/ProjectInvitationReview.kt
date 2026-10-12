package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.ProjectReference
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import dev.forgesworn.kithmoot.ui.room.currentInvitationProjectBrand

/** Display context from this account's joined directory, with no bearer or secret. */
data class ProjectInvitationReview(
    val account: String,
    val npub: String,
    val name: String,
    val project: ProjectReference,
    val room: String,
    val roomName: String = "",
)

internal fun projectInvitationReview(link: String, state: StartState,
    selected: ProjectReference? = null): ProjectInvitationReview? {
    val invitation = runCatching { decodeInvitationUrl(link) }.getOrNull() ?: return null
    if (!invitation.invitation.persistent) return null
    val account = state.account ?: return null
    val brand = currentInvitationProjectBrand(link, state, selected) ?: return null
    if (selected != null && brand.reference != selected) return null
    return ProjectInvitationReview(account.pubkey, account.npub, account.shownName, brand.reference, brand.room, brand.roomName)
}

/** Withdrawal cannot substitute another project sharing this room or link. */
internal fun projectInvitationReviewStillMatches(review: ProjectInvitationReview, link: String, state: StartState): Boolean {
    val current = projectInvitationReview(link, state, review.project) ?: return false
    return current.account == review.account && current.project == review.project && current.room == review.room
}
