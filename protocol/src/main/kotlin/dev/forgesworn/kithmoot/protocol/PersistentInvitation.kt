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

/** A durable bearer envelope, signed by the link's pinned inviter. No delegation is granted. */
fun encodePersistentInvitation(
    host: RoomInvitationHost,
    roomSecret: ByteArray,
    now: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(Schnorr.publicKeyHex(host.inviterSecretKey) == host.invitation.canonicalInviter)
    val room = deriveRoom(roomSecret)
    val body = buildJsonObject {
        put("v", 3)
        put("room", room.roomId)
        put("secret", base64UrlEncode(roomSecret))
    }
    return Events.sign(host.inviterSecretKey, KIND_GROUP_INVITATION, now,
        listOf(listOf("d", deriveInvitationId(host.invitation))),
        Nip44.encrypt(body.toString(), groupInvitationKey(host.invitation), nonce), auxRand)
}

/** Untrusted relay data must never escape as an exception or provide a responder key. */
fun decodePersistentInvitation(event: NostrEvent, invitation: RoomInvitation): RoomAdmission? = try {
    if (!invitation.persistent || event.kind != KIND_GROUP_INVITATION || event.pubkey != invitation.canonicalInviter || !Events.verify(event)) null
    else if (event.tags.count { it.firstOrNull() == "d" } != 1 || event.tagValue("d") != deriveInvitationId(invitation)) null
    else {
        val body = Json.parseToJsonElement(Nip44.decrypt(event.content, groupInvitationKey(invitation))).jsonObject
        require(body.getValue("secret").jsonPrimitive.isString)
        require(!body.getValue("v").jsonPrimitive.isString)
        val secret = base64UrlDecode(body.getValue("secret").jsonPrimitive.content)
        if (body["v"]?.jsonPrimitive?.longOrNull != 3L || deriveRoom(secret).roomId != body["room"]?.jsonPrimitive?.content) null
        else RoomAdmission(secret, null)
    }
} catch (_: Exception) { null }
