package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*

/** Opt-in codecs only; durable root lifecycle and fresh epoch admission are separate gates. */
const val LIVE_PERSISTENT_REQUEST_SECONDS = 90L
const val LIVE_PERSISTENT_RESPONSE_SECONDS = 30L
private const val LIVE_REQUEST_KEY = "kithmoot/v1/persistent-live/request-key"
private const val LIVE_PROFILE = "persistent-live"
private const val LIVE_SAFE_INTEGER = 9_007_199_254_740_991L
private val liveHex = Regex("[0-9a-f]{64}")
private val liveSignature = Regex("[0-9a-f]{128}")

data class LivePersistentContext(val invitation: RoomInvitation, val roomId: String) {
    init {
        require(invitation.persistent && liveHex.matches(invitation.inviter) && liveHex.matches(roomId))
    }
}
data class LivePersistentRequest(val requestId: String, val requester: String, val createdAt: Long, val expiresAt: Long)
data class LivePersistentAnswer(val admission: RoomAdmission, val epochHint: Long, val requestId: String, val expiresAt: Long)

private fun liveClock(now: Long) { require(now in 0..LIVE_SAFE_INTEGER - LIVE_PERSISTENT_REQUEST_SECONDS) }
private fun liveKey(context: LivePersistentContext): ByteArray =
    Digests.hkdfSha256(context.invitation.bearer, null, LIVE_REQUEST_KEY.toByteArray(Charsets.UTF_8), 32)
private fun JsonObject.liveString(name: String): String = getValue(name).jsonPrimitive.let {
    require(it.isString); it.content
}
private fun JsonObject.liveInteger(name: String): Long = getValue(name).jsonPrimitive.let {
    require(!it.isString)
    val n = it.long
    require(n in 0..LIVE_SAFE_INTEGER && n.toString() == it.content)
    n
}
private fun liveBody(value: String, names: Set<String>): JsonObject {
    val raw = Json.parseToJsonElement(value).jsonObject
    require(raw.keys == names && raw.toString() == value)
    return raw
}

/** Validate the raw object before NostrEvent.fromJson can discard extra fields or coerce strings. */
private fun liveEvent(raw: JsonObject, contentLimit: Int, byteLimit: Int, tagLimit: Int): NostrEvent {
    require(raw.keys == setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"))
    require(liveHex.matches(raw.liveString("id")) && liveHex.matches(raw.liveString("pubkey")))
    require(liveSignature.matches(raw.liveString("sig")))
    raw.liveInteger("created_at")
    require(raw.liveInteger("kind") <= Int.MAX_VALUE)
    require(raw.liveString("content").length <= contentLimit)
    val tags = raw.getValue("tags").jsonArray
    require(tags.size <= tagLimit)
    for (tag in tags) {
        require(tag.jsonArray.size <= 4)
        for (v in tag.jsonArray) require(v.jsonPrimitive.isString && v.jsonPrimitive.content.length <= 256)
    }
    require(raw.toString().toByteArray(Charsets.UTF_8).size <= byteLimit)
    return NostrEvent.fromJson(raw)
}

/** Raw network JSON must be byte-bounded before parsing; keep this reader at that boundary. */
fun parseLivePersistentEvent(json: String, request: Boolean): NostrEvent? = try {
    val max = if (request) 4096 else 20480
    require(json.length <= max && json.toByteArray(Charsets.UTF_8).size <= max)
    val raw = Json.parseToJsonElement(json).jsonObject
    require(raw.toString() == json)
    liveEvent(raw, if (request) 2048 else 16384, max, 3)
} catch (_: Exception) { null }

private fun liveEnvelope(event: NostrEvent, context: LivePersistentContext, kind: Int, recipient: String, now: Long): Long {
    liveClock(now)
    val request = kind == KIND_INVITATION_REQUEST
    // Bound typed events before converting their lists to JSON.
    require(event.content.length <= if (request) 2048 else 16384)
    require(event.tags.size == 3 && event.tags.all { it.size == 2 && it.all { v -> v.length <= 256 } })
    require(liveHex.matches(event.id) && liveHex.matches(event.pubkey) && liveSignature.matches(event.sig))
    liveEvent(event.toJson(), if (request) 2048 else 16384, if (request) 4096 else 20480, 3)
    require(event.kind == kind && event.createdAt <= now + 5)
    fun tag(name: String): String = event.tags.single { it[0] == name }[1]
    require(tag("d") == deriveInvitationId(context.invitation) && tag("p") == recipient)
    val expiry = tag("expiration").toLong()
    require(expiry in 0..LIVE_SAFE_INTEGER && expiry.toString() == tag("expiration") && expiry > now)
    require(Events.verify(event))
    return expiry
}

fun encodeLivePersistentDescriptor(context: LivePersistentContext): String = base64UrlEncode(buildJsonObject {
    put("v", 1); put("room", context.roomId)
    put("invitation", deriveInvitationId(context.invitation)); put("inviter", context.invitation.inviter)
}.toString().toByteArray(Charsets.UTF_8))

fun decodeLivePersistentDescriptor(encoded: String, invitation: RoomInvitation): LivePersistentContext? = try {
    require(encoded.length <= 512)
    val bytes = base64UrlDecode(encoded)
    require(bytes.size <= 384 && base64UrlEncode(bytes) == encoded)
    val text = bytes.toString(Charsets.UTF_8)
    require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
    val raw = liveBody(text, setOf("v", "room", "invitation", "inviter"))
    require(raw.liveInteger("v") == 1L && raw.liveString("inviter") == invitation.inviter)
    require(raw.liveString("invitation") == deriveInvitationId(invitation))
    LivePersistentContext(invitation, raw.liveString("room"))
} catch (_: Exception) { null }

fun encodeLivePersistentRequest(
    context: LivePersistentContext, requesterSk: ByteArray, now: Long,
    nonce: ByteArray = Entropy.bytes(32), auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    liveClock(now)
    val body = buildJsonObject {
        put("v", 1); put("profile", LIVE_PROFILE); put("room", context.roomId)
        put("requester", Schnorr.publicKeyHex(requesterSk))
    }
    return Events.sign(requesterSk, KIND_INVITATION_REQUEST, now,
        listOf(listOf("d", deriveInvitationId(context.invitation)), listOf("p", context.invitation.inviter),
            listOf("expiration", (now + LIVE_PERSISTENT_REQUEST_SECONDS).toString())),
        Nip44.encrypt(body.toString(), liveKey(context), nonce), auxRand)
}

fun decodeLivePersistentRequest(event: NostrEvent, context: LivePersistentContext, now: Long): LivePersistentRequest? = try {
    val expiry = liveEnvelope(event, context, KIND_INVITATION_REQUEST, context.invitation.inviter, now)
    require(expiry == event.createdAt + LIVE_PERSISTENT_REQUEST_SECONDS)
    val raw = liveBody(Nip44.decrypt(event.content, liveKey(context)), setOf("v", "profile", "room", "requester"))
    require(raw.liveInteger("v") == 1L && raw.liveString("profile") == LIVE_PROFILE)
    require(raw.liveString("room") == context.roomId && raw.liveString("requester") == event.pubkey)
    LivePersistentRequest(event.id, event.pubkey, event.createdAt, expiry)
} catch (_: Exception) { null }

private fun liveInvitation(raw: JsonObject, context: LivePersistentContext, now: Long, responseAt: Long): RoomAdmission {
    val event = liveEvent(raw, 6144, 8192, 8)
    require(event.createdAt <= minOf(now, responseAt) + 5)
    val admission = requireNotNull(decodePersistentInvitation(event, context.invitation))
    require(deriveRoom(admission.secret).roomId == context.roomId)
    require(admission.endsAt == null || admission.endsAt > now)
    return admission
}

/** Codec only; callers need exclusive durable lifecycle authority before answering. */
fun encodeLivePersistentAnswer(
    context: LivePersistentContext, requestEvent: NostrEvent, invitationEvent: NostrEvent,
    inviterSk: ByteArray, epoch: Long, now: Long,
    nonce: ByteArray = Entropy.bytes(32), auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    val request = requireNotNull(decodeLivePersistentRequest(requestEvent, context, now))
    require(epoch in 0..LIVE_SAFE_INTEGER && Schnorr.publicKeyHex(inviterSk) == context.invitation.inviter)
    require(now >= request.createdAt - 5)
    require(invitationEvent.content.length <= 6144 && invitationEvent.tags.size <= 8)
    require(invitationEvent.tags.all { it.size <= 4 && it.all { v -> v.length <= 256 } })
    liveInvitation(invitationEvent.toJson(), context, now, now)
    val body = buildJsonObject {
        put("v", 1); put("profile", LIVE_PROFILE); put("request", request.requestId)
        put("room", context.roomId); put("epoch", epoch)
        // This profile fixes its embedded event field order independently of older envelopes.
        put("invitation", buildJsonObject {
            put("id", invitationEvent.id); put("pubkey", invitationEvent.pubkey)
            put("created_at", invitationEvent.createdAt); put("kind", invitationEvent.kind)
            put("tags", tagsToJson(invitationEvent.tags)); put("content", invitationEvent.content); put("sig", invitationEvent.sig)
        })
    }
    val event = Events.sign(inviterSk, KIND_INVITATION_GRANT, now,
        listOf(listOf("d", deriveInvitationId(context.invitation)), listOf("p", request.requester),
            listOf("expiration", minOf(request.expiresAt, now + LIVE_PERSISTENT_RESPONSE_SECONDS).toString())),
        Nip44.encrypt(body.toString(), Nip44.conversationKey(inviterSk, request.requester.hexToBytes()), nonce), auxRand)
    liveEvent(event.toJson(), 16384, 20480, 3)
    return event
}

/** Stateless: the request owner consumes success once and erases the one-use key. */
fun decodeLivePersistentAnswer(
    event: NostrEvent, context: LivePersistentContext, requestEvent: NostrEvent, requesterSk: ByteArray, now: Long,
): LivePersistentAnswer? = try {
    val request = requireNotNull(decodeLivePersistentRequest(requestEvent, context, now))
    require(Schnorr.publicKeyHex(requesterSk) == request.requester)
    val expiry = liveEnvelope(event, context, KIND_INVITATION_GRANT, request.requester, now)
    require(event.pubkey == context.invitation.inviter && event.createdAt >= request.createdAt - 5)
    require(expiry == minOf(request.expiresAt, event.createdAt + LIVE_PERSISTENT_RESPONSE_SECONDS))
    val raw = liveBody(Nip44.decrypt(event.content, Nip44.conversationKey(requesterSk, context.invitation.inviter.hexToBytes())),
        setOf("v", "profile", "request", "room", "epoch", "invitation"))
    require(raw.liveInteger("v") == 1L && raw.liveString("profile") == LIVE_PROFILE)
    require(raw.liveString("request") == request.requestId && raw.liveString("room") == context.roomId)
    LivePersistentAnswer(liveInvitation(raw.getValue("invitation").jsonObject, context, now, event.createdAt),
        raw.liveInteger("epoch"), request.requestId, expiry)
} catch (_: Exception) { null }
