package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter

open class GroupInvitationException(message: String) : Exception(message)

/** No relay asked had the invitation. Unlike a retirement or a conflict, another relay might. */
class MissingGroupInvitationException(message: String) : GroupInvitationException(message)

/** What a person is told when the link's relays and their own all came up empty. */
const val INVITATION_NOT_FOUND: String =
    "The group invitation was not found on the link's relays or on yours. If you know a relay the room uses, add it under Relays in the account menu and try again. Otherwise ask someone in the room for a fresh link."

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
    if (admissions.isEmpty()) throw MissingGroupInvitationException(INVITATION_NOT_FOUND)
    if (admissions.map { deriveRoom(it.secret).roomId }.distinct().size != 1) {
        throw GroupInvitationException("This group invitation names conflicting rooms. Ask for a current link.")
    }
    // Copies re-signed over the room's life should all carry one end, or
    // none. When valid copies disagree the earliest end wins, as on the web
    // (fold-kit's requestPersistentRoomAdmission): a copy with no end never
    // removes an end another copy carries, and refusing would open the link
    // on one client and not the other.
    val endsAt = admissions.mapNotNull { it.endsAt }.minOrNull()
    return admissions.first().copy(endsAt = endsAt)
}
