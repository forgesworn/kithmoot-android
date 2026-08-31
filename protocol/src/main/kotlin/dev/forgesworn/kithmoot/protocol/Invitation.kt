package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val KIND_INVITATION_REQUEST: Int = 20466
const val KIND_INVITATION_GRANT: Int = 20467
const val KIND_INVITATION_RETIREMENT: Int = 1461
const val INVITATION_ID_INFO: String = "kithmoot/v2/invitation-id"
const val INVITATION_REQUEST_KEY_INFO: String = "kithmoot/v2/invitation-request-key"
const val INVITATION_MAX_AGE_SECONDS: Long = 90
const val INVITATION_DELEGATION_TTL_SECONDS: Long = 12 * 60 * 60
const val MAX_INVITATION_DELEGATION_DEPTH: Int = 16

/** A share-link capability. The bearer is not the room traffic secret. */
class RoomInvitation(val bearer: ByteArray, val inviter: String) {
    init {
        require(bearer.size == 32) { "an invitation bearer is 32 bytes" }
        require(inviter.matches(Regex("^[0-9a-fA-F]{64}$"))) { "an inviter is a 32-byte hex pubkey" }
    }

    val canonicalInviter: String = inviter.lowercase()

    override fun equals(other: Any?): Boolean =
        other is RoomInvitation && bearer.contentEquals(other.bearer) && canonicalInviter == other.canonicalInviter

    override fun hashCode(): Int = 31 * bearer.contentHashCode() + canonicalInviter.hashCode()
}

data class InvitationDelegation(
    val invitation: String,
    val room: String,
    val issuer: String,
    val delegate: String,
    val expiresAt: Long,
    val sig: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("invitation", invitation)
        put("room", room)
        put("issuer", issuer)
        put("delegate", delegate)
        put("expiresAt", expiresAt)
        put("sig", sig)
    }

    companion object {
        fun fromJson(json: JsonObject): InvitationDelegation = InvitationDelegation(
            invitation = json.getValue("invitation").jsonPrimitive.content.lowercase(),
            room = json.getValue("room").jsonPrimitive.content.lowercase(),
            issuer = json.getValue("issuer").jsonPrimitive.content.lowercase(),
            delegate = json.getValue("delegate").jsonPrimitive.content.lowercase(),
            expiresAt = json.getValue("expiresAt").jsonPrimitive.long,
            sig = json.getValue("sig").jsonPrimitive.content.lowercase(),
        )
    }
}

/** A root creator or an admitted member delegated to keep this link live. */
class RoomInvitationHost(
    val invitation: RoomInvitation,
    val inviterSecretKey: ByteArray,
    val delegation: List<InvitationDelegation> = emptyList(),
) {
    init {
        require(inviterSecretKey.size == 32) { "an inviter secret key is 32 bytes" }
        require(
            verifyInvitationDelegation(invitation, delegation, 0L) == Schnorr.publicKeyHex(inviterSecretKey),
        ) {
            "the responder is not delegated for the invitation"
        }
    }
}

data class RoomAdmission(val secret: ByteArray, val delegate: RoomInvitationHost)

class InvitationPayload(
    val invitation: RoomInvitation,
    val relays: List<String>,
    val policy: RoomPolicy?,
)

fun createRoomInvitation(): RoomInvitationHost {
    val secretKey = Entropy.bytes(32)
    return RoomInvitationHost(
        RoomInvitation(Entropy.bytes(32), Schnorr.publicKeyHex(secretKey)),
        secretKey,
    )
}

fun deriveInvitationId(invitation: RoomInvitation): String =
    Digests.hkdfSha256(
        invitation.bearer,
        null,
        INVITATION_ID_INFO.toByteArray(Charsets.UTF_8),
        32,
    ).toHex()

private fun invitationRequestKey(invitation: RoomInvitation): ByteArray =
    Digests.hkdfSha256(
        invitation.bearer,
        null,
        INVITATION_REQUEST_KEY_INFO.toByteArray(Charsets.UTF_8),
        32,
    )

fun encodeInvitationUrl(
    base: String,
    invitation: RoomInvitation,
    relays: List<String>,
    policy: RoomPolicy? = null,
): String {
    val payload = buildJsonObject {
        put("v", 2)
        put("j", base64UrlEncode(invitation.bearer))
        put("h", invitation.canonicalInviter)
        put("r", buildJsonArray { for (relay in relays) add(JsonPrimitive(relay)) })
        if (policy != null) put("a", policy.toJson())
    }
    return "$base#${base64UrlEncode(payload.toString().toByteArray(Charsets.UTF_8))}"
}

/** Returns null for a legacy v1 link and throws for a malformed v2 link. */
fun decodeInvitationUrl(url: String): InvitationPayload? {
    val fragment = url.substringAfter('#', "")
    if (fragment.isEmpty()) throw JoinUrlException("join URL fragment is not valid")
    val payload = try {
        Json.parseToJsonElement(String(base64UrlDecode(fragment), Charsets.UTF_8)).jsonObject
    } catch (_: Exception) {
        throw JoinUrlException("join URL fragment is not valid")
    }
    if (payload["v"]?.jsonPrimitive?.longOrNull != 2L) return null

    val bearer = try {
        base64UrlDecode(payload.getValue("j").jsonPrimitive.content)
    } catch (_: Exception) {
        throw JoinUrlException("join URL carries a malformed invitation")
    }
    val inviter = try {
        payload.getValue("h").jsonPrimitive.content
    } catch (_: Exception) {
        throw JoinUrlException("join URL carries a malformed invitation")
    }
    val invitation = try {
        RoomInvitation(bearer, inviter)
    } catch (_: Exception) {
        throw JoinUrlException("join URL carries a malformed invitation")
    }
    val relays: List<String> = try {
        (payload["r"] as? JsonArray)?.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        null
    } ?: throw JoinUrlException("join URL fragment is not valid")
    val policyJson = payload["a"]
    val policy = if (policyJson == null) {
        null
    } else {
        val json = policyJson as? JsonObject ?: throw JoinUrlException("join URL fragment is not valid")
        RoomPolicy.fromJson(json)
            ?: throw JoinUrlException("join URL carries an access policy at an unknown tier")
    }
    return InvitationPayload(invitation, relays, policy)
}

data class InvitationRequest(val device: String, val requestId: String)

fun encodeInvitationRequest(
    invitation: RoomInvitation,
    requesterSecretKey: ByteArray,
    now: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    val device = Schnorr.publicKeyHex(requesterSecretKey)
    val body = buildJsonObject {
        put("v", 1)
        put("device", device)
    }
    return Events.sign(
        secretKey = requesterSecretKey,
        kind = KIND_INVITATION_REQUEST,
        createdAt = now,
        tags = listOf(
            listOf("d", deriveInvitationId(invitation)),
            listOf("p", invitation.canonicalInviter),
        ),
        content = Nip44.encrypt(body.toString(), invitationRequestKey(invitation), nonce),
        auxRand = auxRand,
    )
}

fun decodeInvitationRequest(
    event: NostrEvent,
    invitation: RoomInvitation,
    now: Long,
    maxAgeSeconds: Long = INVITATION_MAX_AGE_SECONDS,
): InvitationRequest? {
    return try {
        if (event.kind != KIND_INVITATION_REQUEST || !Events.verify(event)) return null
        if (kotlin.math.abs(now - event.createdAt) > maxAgeSeconds) return null
        if (event.tagValue("d") != deriveInvitationId(invitation)) return null
        if (!event.tagValue("p").equals(invitation.canonicalInviter, ignoreCase = true)) return null
        val body = Json.parseToJsonElement(
            Nip44.decrypt(event.content, invitationRequestKey(invitation)),
        ).jsonObject
        if (body["v"]?.jsonPrimitive?.longOrNull != 1L) return null
        val device = body.getValue("device").jsonPrimitive.content.lowercase()
        if (!device.matches(Regex("^[0-9a-f]{64}$")) || device != event.pubkey.lowercase()) return null
        InvitationRequest(device, event.id.lowercase())
    } catch (_: Exception) {
        null
    }
}

private fun delegationMessage(
    invitation: String,
    room: String,
    issuer: String,
    delegate: String,
    expiresAt: Long,
): ByteArray = Digests.sha256(
    "kithmoot/v2/invitation-delegation:$invitation:$room:$issuer:$delegate:$expiresAt"
        .toByteArray(Charsets.UTF_8),
)

private fun issueInvitationDelegation(
    invitation: RoomInvitation,
    room: String,
    issuerSecretKey: ByteArray,
    delegate: String,
    expiresAt: Long,
): InvitationDelegation {
    require(delegate.matches(Regex("^[0-9a-fA-F]{64}$"))) { "a delegate is a 32-byte hex pubkey" }
    require(expiresAt > 0) { "a delegation expiry is unix seconds" }
    val invitationId = deriveInvitationId(invitation)
    require(room.matches(Regex("^[0-9a-fA-F]{64}$"))) { "a room id is 32-byte hex" }
    val canonicalRoom = room.lowercase()
    val issuer = Schnorr.publicKeyHex(issuerSecretKey)
    val canonicalDelegate = delegate.lowercase()
    return InvitationDelegation(
        invitation = invitationId,
        room = canonicalRoom,
        issuer = issuer,
        delegate = canonicalDelegate,
        expiresAt = expiresAt,
        sig = Schnorr.sign(
            delegationMessage(invitationId, canonicalRoom, issuer, canonicalDelegate, expiresAt),
            issuerSecretKey,
        ).toHex(),
    )
}

/** Returns the final authorised pubkey, or null for a malformed, expired or
 * incorrectly rooted chain. An empty chain authorises the inviter in the URL. */
fun verifyInvitationDelegation(
    invitation: RoomInvitation,
    chain: List<InvitationDelegation>,
    now: Long,
): String? {
    return try {
        if (chain.size > MAX_INVITATION_DELEGATION_DEPTH) return null
        val invitationId = deriveInvitationId(invitation)
        var room: String? = null
        var authority = invitation.canonicalInviter
        for (certificate in chain) {
            if (!certificate.invitation.equals(invitationId, ignoreCase = true)) return null
            if (!certificate.room.matches(Regex("^[0-9a-fA-F]{64}$"))) return null
            if (room != null && !certificate.room.equals(room, ignoreCase = true)) return null
            room = certificate.room.lowercase()
            if (!certificate.issuer.equals(authority, ignoreCase = true)) return null
            if (certificate.expiresAt <= now) return null
            if (!certificate.issuer.matches(Regex("^[0-9a-fA-F]{64}$"))) return null
            if (!certificate.delegate.matches(Regex("^[0-9a-fA-F]{64}$"))) return null
            if (!certificate.sig.matches(Regex("^[0-9a-fA-F]{128}$"))) return null
            if (!Schnorr.verify(
                    certificate.sig.hexToBytes(),
                    delegationMessage(
                        certificate.invitation.lowercase(),
                        certificate.room.lowercase(),
                        certificate.issuer.lowercase(),
                        certificate.delegate.lowercase(),
                        certificate.expiresAt,
                    ),
                    certificate.issuer.hexToBytes(),
                )
            ) return null
            authority = certificate.delegate.lowercase()
        }
        authority
    } catch (_: Exception) {
        null
    }
}

fun encodeInvitationGrant(
    host: RoomInvitationHost,
    requester: String,
    requestId: String,
    roomSecret: ByteArray,
    now: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(roomSecret.size == 32) { "a room secret is 32 bytes" }
    require(requester.matches(Regex("^[0-9a-fA-F]{64}$"))) { "a requester is a 32-byte hex pubkey" }
    require(requestId.matches(Regex("^[0-9a-fA-F]{64}$"))) { "a request id is 32-byte hex" }
    val authority = verifyInvitationDelegation(host.invitation, host.delegation, now)
    require(authority == Schnorr.publicKeyHex(host.inviterSecretKey)) {
        "the responder is not delegated for the invitation"
    }
    val roomId = deriveRoom(roomSecret).roomId
    require(host.delegation.isEmpty() || host.delegation.first().room.equals(roomId, ignoreCase = true)) {
        "the delegation names another room"
    }
    require(host.delegation.size < MAX_INVITATION_DELEGATION_DEPTH) {
        "the invitation delegation is at maximum depth"
    }
    val canonicalRequester = requester.lowercase()
    val authorityExpiry = host.delegation.minOfOrNull { it.expiresAt } ?: Long.MAX_VALUE
    val expiresAt = minOf(authorityExpiry, now + INVITATION_DELEGATION_TTL_SECONDS)
    val next = issueInvitationDelegation(host.invitation, roomId, host.inviterSecretKey, canonicalRequester, expiresAt)
    val chain = host.delegation + next
    val body = buildJsonObject {
        put("v", 2)
        put("request", requestId.lowercase())
        put("secret", base64UrlEncode(roomSecret))
        put("delegation", buildJsonArray { for (certificate in chain) add(certificate.toJson()) })
    }
    val conversationKey = Nip44.conversationKey(host.inviterSecretKey, canonicalRequester.hexToBytes())
    return Events.sign(
        secretKey = host.inviterSecretKey,
        kind = KIND_INVITATION_GRANT,
        createdAt = now,
        tags = listOf(
            listOf("d", deriveInvitationId(host.invitation)),
            listOf("p", canonicalRequester),
        ),
        content = Nip44.encrypt(body.toString(), conversationKey, nonce),
        auxRand = auxRand,
    )
}

fun decodeInvitationGrant(
    event: NostrEvent,
    invitation: RoomInvitation,
    requesterSecretKey: ByteArray,
    requestId: String,
    now: Long,
    maxAgeSeconds: Long = INVITATION_MAX_AGE_SECONDS,
): ByteArray? = decodeRoomAdmissionGrant(
    event,
    invitation,
    requesterSecretKey,
    requestId,
    now,
    maxAgeSeconds,
)?.secret

fun decodeRoomAdmissionGrant(
    event: NostrEvent,
    invitation: RoomInvitation,
    requesterSecretKey: ByteArray,
    requestId: String,
    now: Long,
    maxAgeSeconds: Long = INVITATION_MAX_AGE_SECONDS,
): RoomAdmission? {
    return try {
        if (event.kind != KIND_INVITATION_GRANT || !Events.verify(event)) return null
        if (kotlin.math.abs(now - event.createdAt) > maxAgeSeconds) return null
        if (event.tagValue("d") != deriveInvitationId(invitation)) return null
        val requester = Schnorr.publicKeyHex(requesterSecretKey)
        if (!event.tagValue("p").equals(requester, ignoreCase = true)) return null
        val key = Nip44.conversationKey(requesterSecretKey, event.pubkey.hexToBytes())
        val body = Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject
        if (body["v"]?.jsonPrimitive?.longOrNull != 2L) return null
        if (!body.getValue("request").jsonPrimitive.content.equals(requestId, ignoreCase = true)) return null
        val secret = base64UrlDecode(body.getValue("secret").jsonPrimitive.content)
        if (secret.size != 32) return null
        val delegation = body.getValue("delegation").jsonArray.map {
            InvitationDelegation.fromJson(it.jsonObject)
        }
        if (delegation.isEmpty()) return null
        if (!delegation.first().room.equals(deriveRoom(secret).roomId, ignoreCase = true)) return null
        if (verifyInvitationDelegation(invitation, delegation, now) != requester) return null
        if (!delegation.last().issuer.equals(event.pubkey, ignoreCase = true)) return null
        RoomAdmission(secret, RoomInvitationHost(invitation, requesterSecretKey, delegation))
    } catch (_: Exception) {
        null
    }
}

fun encodeInvitationRetirement(
    invitation: RoomInvitation,
    inviterSecretKey: ByteArray,
    now: Long,
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(Schnorr.publicKeyHex(inviterSecretKey) == invitation.canonicalInviter) {
        "only the root inviter can retire an invitation"
    }
    return Events.sign(
        secretKey = inviterSecretKey,
        kind = KIND_INVITATION_RETIREMENT,
        createdAt = now,
        tags = listOf(listOf("d", deriveInvitationId(invitation))),
        content = "{\"v\":1}",
        auxRand = auxRand,
    )
}

fun decodeInvitationRetirement(event: NostrEvent, invitation: RoomInvitation): Boolean = try {
    event.kind == KIND_INVITATION_RETIREMENT &&
        Events.verify(event) &&
        event.pubkey.equals(invitation.canonicalInviter, ignoreCase = true) &&
        event.tagValue("d") == deriveInvitationId(invitation) &&
        Json.parseToJsonElement(event.content).jsonObject["v"]?.jsonPrimitive?.longOrNull == 1L
} catch (_: Exception) {
    false
}
