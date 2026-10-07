package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.RoomAdmission
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.deriveRoom

/** A group this account has joined on another device, found by the
 *  persistent invitation its bookmark names, and the secret that lets this
 *  device in without the group's signed invitation, which public relays drop
 *  within a day or two. Contains a secret. */
class SyncedGroup(val room: AccountRoom, val admission: RoomAdmission) {
    override fun toString(): String = "SyncedGroup(${room.roomId}, secret=<redacted>)"
}

/**
 * The bookmark naming [invitation] whose secret derives the bookmark's own room
 * id, as the admission a fetch of the group invitation would give: the secret
 * and no delegate. Null for a link that is not a persistent group, a bookmark
 * without a secret or with one that is not the room's own, and two bookmarks
 * naming the same invitation for different rooms. The caller opens a room the
 * phone already keeps through its saved record, not through this.
 * Mirrors `groupAdmissions().adopt` in the web client's app/src/main.ts.
 */
fun syncedGroup(rooms: List<AccountRoom>, invitation: RoomInvitation): SyncedGroup? {
    if (!invitation.persistent) return null
    val named = rooms.filter { room ->
        runCatching { decodeInvitationUrl(room.link)?.invitation == invitation }.getOrDefault(false)
    }
    if (named.map { it.roomId }.distinct().size != 1) return null
    val room = named.first()
    val secret = syncedSecret(room) ?: return null
    // The end and self-destruct the bookmark carries come with it, so an
    // ended room is refused and a self-destructing one is known as such.
    return SyncedGroup(room, RoomAdmission(secret, null, endsAt = room.endsAt, destruct = room.destruct))
}

/** The bookmark's secret, when it derives the room's own id. */
fun syncedSecret(room: AccountRoom): ByteArray? {
    val hex = room.admission?.takeIf { SECRET.matches(it) } ?: return null
    val secret = hex.hexToBytes()
    if (runCatching { deriveRoom(secret).roomId == room.roomId }.getOrDefault(false)) return secret
    secret.fill(0); return null
}

private val SECRET = Regex("[0-9a-f]{64}")
