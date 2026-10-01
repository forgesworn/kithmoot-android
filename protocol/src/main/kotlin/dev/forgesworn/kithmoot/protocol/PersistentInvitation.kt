package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.*

const val KIND_GROUP_INVITATION = 1463
private const val GROUP_INVITATION_KEY_INFO = "kithmoot/v3/group-invitation-key"

private fun groupInvitationKey(invitation: RoomInvitation): ByteArray {
    require(invitation.persistent)
    return Digests.hkdfSha256(invitation.bearer, null, GROUP_INVITATION_KEY_INFO.toByteArray(Charsets.UTF_8), 32)
}

/**
 * A durable bearer envelope, signed by the link's pinned inviter. No delegation is granted.
 *
 * A conference room's [ends] rides in the body and, as a NIP-40 `expiration`,
 * on the event, so relays drop the way in when the room ends. Without it the
 * envelope is exactly what it always was.
 */
fun encodePersistentInvitation(
    host: RoomInvitationHost,
    roomSecret: ByteArray,
    now: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
    ends: Long? = null,
): NostrEvent {
    require(Schnorr.publicKeyHex(host.inviterSecretKey) == host.invitation.canonicalInviter)
    ends?.let { require(it > now) { "this conference room has ended" } }
    val room = deriveRoom(roomSecret)
    val body = buildJsonObject {
        put("v", 3)
        put("room", room.roomId)
        put("secret", base64UrlEncode(roomSecret))
        ends?.let { put("ends", it) }
    }
    return Events.sign(host.inviterSecretKey, KIND_GROUP_INVITATION, now,
        withRoomExpiration(listOf(listOf("d", deriveInvitationId(host.invitation))), ends),
        Nip44.encrypt(body.toString(), groupInvitationKey(host.invitation), nonce), auxRand)
}

/**
 * Untrusted relay data must never escape as an exception or provide a responder key.
 *
 * A conference room's end comes back as [RoomAdmission.endsAt]. An `ends`
 * that is not a positive safe integer, a second `expiration` tag, or an
 * `expiration` that is not exactly the body's `ends` refuses the whole
 * envelope: the tag is what relays act on, so it must say what the room says.
 */
fun decodePersistentInvitation(event: NostrEvent, invitation: RoomInvitation): RoomAdmission? = try {
    if (!invitation.persistent || event.kind != KIND_GROUP_INVITATION || event.pubkey != invitation.canonicalInviter || !Events.verify(event)) null
    else if (event.tags.count { it.firstOrNull() == "d" } != 1 || event.tagValue("d") != deriveInvitationId(invitation)) null
    else {
        val body = Json.parseToJsonElement(Nip44.decrypt(event.content, groupInvitationKey(invitation))).jsonObject
        require(body.getValue("secret").jsonPrimitive.isString)
        require(!body.getValue("v").jsonPrimitive.isString)
        val secret = base64UrlDecode(body.getValue("secret").jsonPrimitive.content)
        val ends = if ("ends" in body) requireNotNull(conferenceEndsOf(body["ends"])) else null
        val expirations = event.tags.filter { it.firstOrNull() == "expiration" }
        require(expirations.size <= 1)
        expirations.singleOrNull()?.let { require(ends != null && it.getOrNull(1) == ends.toString()) }
        if (body["v"]?.jsonPrimitive?.longOrNull != 3L || deriveRoom(secret).roomId != body["room"]?.jsonPrimitive?.content) null
        else RoomAdmission(secret, null, ends)
    }
} catch (_: Exception) { null }
