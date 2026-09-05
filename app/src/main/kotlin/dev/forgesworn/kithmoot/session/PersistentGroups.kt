package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter

class GroupInvitationException(message: String) : Exception(message)

/** The query must include tombstones and reach EOSE before any stored welcome is used. */
suspend fun requestPersistentAdmission(
    invitation: RoomInvitation,
    query: suspend (List<Filter>) -> List<NostrEvent>,
): RoomAdmission {
    require(invitation.persistent)
    val events = query(listOf(Filter(kinds = listOf(KIND_GROUP_INVITATION, KIND_INVITATION_RETIREMENT),
        authors = listOf(invitation.canonicalInviter), tags = mapOf("#d" to listOf(deriveInvitationId(invitation))))))
    if (events.any { decodeInvitationRetirement(it, invitation) }) {
        throw GroupInvitationException("This invitation was retired. Ask for the current room link.")
    }
    val admissions = events.mapNotNull { decodePersistentInvitation(it, invitation) }
    if (admissions.isEmpty()) throw GroupInvitationException("The group invitation is unavailable on its relays. Try again or ask for a current link.")
    if (admissions.map { deriveRoom(it.secret).roomId }.distinct().size != 1) {
        throw GroupInvitationException("This group invitation names conflicting rooms. Ask for a current link.")
    }
    return admissions.first()
}
