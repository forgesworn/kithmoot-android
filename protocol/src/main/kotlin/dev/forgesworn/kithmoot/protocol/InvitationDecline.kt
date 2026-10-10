package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*

data class InvitationDecline(val requestId: String, val responder: String)

private const val MAX_DECLINE_TIME = 9_007_199_254_740_991L
private val declineHex = Regex("^[0-9a-fA-F]{64}$")

/** fold-kit 0.12.0's encrypted version-3 refusal. No room key or new authority. */
fun encodeInvitationDecline(host: RoomInvitationHost, requester: String, requestId: String,
    now: Long, nonce: ByteArray = Entropy.bytes(32), auxRand: ByteArray = Entropy.bytes(32)): NostrEvent {
    require(now in 0..MAX_DECLINE_TIME) { "invalid invitation decline time" }
    require(requester.matches(declineHex) && requestId.matches(declineHex)) { "request and requester must be 32-byte hex" }
    require(host.delegation.all { it.expiresAt in 0..MAX_DECLINE_TIME })
    require(verifyInvitationDelegation(host.invitation, host.delegation, now) == Schnorr.publicKeyHex(host.inviterSecretKey)) {
        "responder is not delegated for invitation"
    }
    val target = requester.lowercase()
    val body = buildJsonObject {
        put("v", 3); put("decision", "declined"); put("request", requestId.lowercase())
        put("delegation", buildJsonArray { host.delegation.forEach { add(it.toJson()) } })
    }
    return Events.sign(host.inviterSecretKey, KIND_INVITATION_GRANT, now,
        listOf(listOf("d", deriveInvitationId(host.invitation)), listOf("p", target)),
        Nip44.encrypt(body.toString(), Nip44.conversationKey(host.inviterSecretKey, target.hexToBytes()), nonce), auxRand)
}

/** Only the pinned root or a current delegate can refuse this exact device/request. */
fun decodeInvitationDecline(event: NostrEvent, invitation: RoomInvitation, requesterKey: ByteArray,
    requestId: String, now: Long, maxAgeSeconds: Long = INVITATION_MAX_AGE_SECONDS): InvitationDecline? = try {
    if (now !in 0..MAX_DECLINE_TIME || event.createdAt !in 0..MAX_DECLINE_TIME || maxAgeSeconds !in 0..MAX_DECLINE_TIME ||
        event.kind != KIND_INVITATION_GRANT || !Events.verify(event) || kotlin.math.abs(now - event.createdAt) > maxAgeSeconds) null
    else {
        val rendezvous = event.tags.filter { it.firstOrNull() == "d" }
        val addressed = event.tags.filter { it.firstOrNull() == "p" }
        val device = Schnorr.publicKeyHex(requesterKey)
        if (rendezvous.size != 1 || rendezvous.single().getOrNull(1) != deriveInvitationId(invitation) ||
            addressed.size != 1 || !addressed.single().getOrNull(1).equals(device, ignoreCase = true)) null
        else {
            val body = Json.parseToJsonElement(Nip44.decrypt(event.content,
                Nip44.conversationKey(requesterKey, event.pubkey.hexToBytes()))).jsonObject
            val version = body["v"] as? JsonPrimitive
            val decision = body["decision"] as? JsonPrimitive
            val request = (body["request"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val chain = (body["delegation"] as? JsonArray)?.map { InvitationDelegation.fromJson(it.jsonObject) }
            if (version?.isString != false || version.longOrNull != 3L || decision?.isString != true || decision.content != "declined" ||
                request == null || !request.matches(declineHex) || !request.equals(requestId, ignoreCase = true) || "secret" in body || chain == null ||
                chain.any { it.expiresAt !in 0..MAX_DECLINE_TIME } ||
                !verifyInvitationDelegation(invitation, chain, now).equals(event.pubkey, ignoreCase = true)) null
            else InvitationDecline(request.lowercase(), event.pubkey.lowercase())
        }
    }
} catch (_: Exception) { null }
