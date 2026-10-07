package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter

open class GroupInvitationException(message: String) : Exception(message)

/** No relay asked had the invitation. Unlike a retirement or a conflict, another relay might. */
class MissingGroupInvitationException(message: String) : GroupInvitationException(message)

/** What a person is told when the link's relays and their own all came up empty. */
const val INVITATION_NOT_FOUND: String =
    "The group invitation was not found on the link's relays or on yours. If you know a relay the room uses, add it under Relays in the account menu and try again. Otherwise ask someone in the room for a fresh link."

/** How often, and how far apart, an empty answer is asked again. See [requestPersistentAdmission]. */
internal val GROUP_INVITATION_RETRY_DELAYS_MS = listOf(1_500L, 3_000L, 4_500L)

/**
 * The query must include tombstones and reach EOSE before any stored welcome is used.
 *
 * An empty answer is asked again before it is believed. A stored query goes to
 * the relays open at that moment, and it goes as soon as ONE of them is: a link
 * whose invitation reached only one of its three relays, opened while the empty
 * ones connected first, used to say the invitation was unavailable without ever
 * asking the relay that held it. Each retry reaches whichever relays have come
 * up since. A retirement is still believed at once.
 */
suspend fun requestPersistentAdmission(
    invitation: RoomInvitation,
    query: suspend (List<Filter>) -> List<NostrEvent>,
): RoomAdmission {
    require(invitation.persistent)
    val filters = listOf(Filter(kinds = listOf(KIND_GROUP_INVITATION, KIND_INVITATION_RETIREMENT),
        authors = listOf(invitation.canonicalInviter), tags = mapOf("#d" to listOf(deriveInvitationId(invitation)))))
    var events = query(filters)
    for (wait in GROUP_INVITATION_RETRY_DELAYS_MS) {
        if (events.any { decodeInvitationRetirement(it, invitation) || decodePersistentInvitation(it, invitation) != null }) break
        kotlinx.coroutines.delay(wait)
        events = query(filters)
    }
    if (events.any { decodeInvitationRetirement(it, invitation) }) {
        throw GroupInvitationException("This invitation was retired. Ask for the current room link.")
    }
    val copies = events.mapNotNull { event -> decodePersistentInvitation(event, invitation)?.let { event to it } }
    val admissions = copies.map { it.second }
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
    // The room's relays, likewise as on the web: the newest copy that names
    // any stands, a copy naming none says nothing about them, and between
    // copies signed in the same second the first heard stays.
    val relays = copies.filter { it.second.relays != null }.reduceOrNull { kept, next -> if (next.first.createdAt > kept.first.createdAt) next else kept }?.second?.relays
    // Self-destruct sticks, as on the web (fold-kit 0.9.0): any valid copy
    // that says so makes the room self-destruct, whichever order they come
    // in, so a stale or careless copy can never keep its content alive.
    val destruct = admissions.any { it.destruct }
    return admissions.first().copy(endsAt = endsAt, relays = relays, destruct = destruct)
}
