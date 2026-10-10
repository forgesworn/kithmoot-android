package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

const val KIND_ROOM_REKEY = 1462
const val KIND_EPOCH_REQUEST = 20_468
const val KIND_EPOCH_GRANT = 20_469
const val MAX_EPOCH = 1_000_000
const val EPOCH_MAX_AGE_SECONDS = 90L

private const val EPOCH_ID_INFO = "kithmoot/v1/epoch-id"
private const val EPOCH_KEY_INFO = "kithmoot/v1/epoch-key"
/** HKDF info for the key an epoch request's admission proof is made under: its own domain, like the media key's. */
private const val EPOCH_REQUEST_KEY_INFO = "kithmoot/v1/epoch-request-key"
private const val EPOCH_REQUEST_MESSAGE = "kithmoot/v1/epoch-request:"
/** The epoch commitment's message prefix; see [epochCommitment]. */
const val EPOCH_COMMIT_PREFIX = "kithmoot/v1/epoch-commit:"
private val HEX64 = Regex("[0-9a-fA-F]{64}")
private val EPOCH_TAG = Regex("^[1-9][0-9]{0,6}$")

class RoomEpoch(val epoch: Int, secret: ByteArray) {
    val secret = secret.copyOf()
    init {
        require(epoch in 0..MAX_EPOCH) { "epoch must be a small non-negative integer" }
        require(secret.size == 32) { "epoch secret must be 32 bytes" }
    }
    override fun toString() = "RoomEpoch(epoch=$epoch, secret=<32 bytes>)"
}

class EpochKeys(val epoch: Int, val id: String, key: ByteArray) {
    val key = key.copyOf()
    init {
        require(epoch in 0..MAX_EPOCH) { "epoch must be a small non-negative integer" }
        require(HEX64.matches(id)) { "epoch id must be 32-byte hex" }
        require(key.size == 32) { "epoch key must be 32 bytes" }
    }
    override fun toString() = "EpochKeys(epoch=$epoch, id=$id, key=<32 bytes>)"
}

class RekeyNotice(
    val epoch: Int,
    removed: List<String>,
    val by: String?,
    val closed: Boolean,
    secret: ByteArray?,
    val at: Long,
    /** True only for an authority grant that proves the current epoch after a gap. */
    val catchUp: Boolean = false,
    /** The rekey body's [epochCommitment], when the authority wrote one. */
    val commit: String? = null,
    /** The authority's member list (#207), when the rekey or grant carried one. */
    members: List<String>? = null,
    /**
     * A scheduled turn of the key rather than a removal: the body said so, nobody was removed
     * and the room stays open. Moving to it needs no announcement. See fold-kit
     * `docs/scheduled-rekey.md`.
     */
    val scheduled: Boolean = false,
    /**
     * The closing rekey says the room self-destructs (fold-kit 0.9.0): every member's device
     * deletes what it wrote and forgets the room. Believed only beside [closed], and only for
     * the value `true`; a body carrying it on an open rekey is read in full without it.
     */
    val destruct: Boolean = false,
) {
    val removed = removed.toList()
    val secret = secret?.copyOf()
    val members = members?.toList()
}

data class EpochRequest(val device: String, val participant: String, val request: String)

/** Qualified enrolment evidence, returned only after request, admission and
 * policy verification. Public credential data is frozen independently of
 * mutable event/tag lists; every reader receives its own copy. */
class VerifiedEpochRequest internal constructor(val request: EpochRequest,
    credential: NostrEvent, val verifiedAt: Long) {
    private val credentialJson = credential.toCompactJson()
    fun credential(): NostrEvent = NostrEvent.fromJson(Json.parseToJsonElement(credentialJson))
}

sealed interface EpochGrant {
    class Current(
        val epoch: Int,
        secret: ByteArray?,
        removed: List<String>,
        members: List<String>? = null,
        /** The epochs the room has left that are still read, oldest first, when the authority's
         *  answer carried them (`passed`): a newcomer reads the last month, not only [epoch]. */
        passed: List<LeftEpoch> = emptyList(),
    ) : EpochGrant {
        val secret = secret?.copyOf()
        val removed = removed.toList()
        /** The participants the room knows (#207), when the authority's answer carried them. */
        val members = members?.toList()
        val passed = passed.map { LeftEpoch(it.epoch, it.secret, it.leftAt) }
    }
    data class Refused(val reason: String) : EpochGrant
}

/**
 * A member list as a body carries it (#207): lower-case, deduplicated and sorted, or null when
 * the field is absent or is not a list of 32-byte hex keys. Null is the safe reading, since it
 * makes nobody known. Matches fold-kit's `readMemberList`.
 */
fun readMemberList(raw: kotlinx.serialization.json.JsonElement?): List<String>? {
    val list = raw as? JsonArray ?: return null
    val keys = list.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    if (!keys.all(HEX64::matches)) return null
    return keys.map(String::normaliseHex).distinct().sorted()
}

/** Why the authority would not hand an epoch over. `unknown` is not final (#207): the room has
 *  removed somebody and does not know this participant yet, so it waits for a member to let them in. */
val EPOCH_REFUSALS = setOf("removed", "closed", "unknown")

fun deriveEpoch(value: RoomEpoch): EpochKeys {
    if (value.epoch == 0) {
        val room = deriveRoom(value.secret)
        return EpochKeys(0, room.roomId, room.roomKey)
    }
    return EpochKeys(
        value.epoch,
        Digests.hkdfSha256(value.secret, null, "$EPOCH_ID_INFO/${value.epoch}".toByteArray(Charsets.UTF_8), 32).toHex(),
        Digests.hkdfSha256(value.secret, null, "$EPOCH_KEY_INFO/${value.epoch}".toByteArray(Charsets.UTF_8), 32),
    )
}

fun peekRekeyEpoch(event: NostrEvent, roomId: String, authority: String): Int? = runCatching {
    if (event.kind != KIND_ROOM_REKEY || !event.pubkey.hexEquals(authority)) return null
    if (event.tagValue("d")?.hexEquals(requireEpochHex(roomId, "room id")) != true) return null
    val tag = event.tagValue("epoch") ?: return null
    if (!EPOCH_TAG.matches(tag)) return null
    val epoch = tag.toIntOrNull()?.takeIf { it <= MAX_EPOCH } ?: return null
    if (!Events.verify(event)) return null
    epoch
}.getOrNull()

fun encodeRekeyEvent(
    roomId: String,
    authoritySecretKey: ByteArray,
    current: EpochKeys,
    next: RoomEpoch,
    recipients: List<String>,
    removed: List<String>,
    now: Long,
    by: String? = null,
    closed: Boolean = false,
    /**
     * Write the [epochCommitment] into the body, so any current member can
     * bring a device that missed this rekey up to date (see `MemberEpoch.kt`).
     * Off, the event is byte-identical to a rekey written before it existed.
     */
    commit: Boolean = false,
    /** The authority's member list (#207): written after `commit`, removed participants
     *  dropped. Null, the event is byte-identical to before. */
    members: List<String>? = null,
    /** Mark a scheduled turn of the key (`"scheduled": true`, after `closed`). Never beside a
     *  removal or a close. Off, the event is byte-identical to before. */
    scheduled: Boolean = false,
    /** Mark a closure as a self-destruct (`"destruct": true`, after `closed`). Only with
     *  [closed]. Off, the event is byte-identical to before. */
    destruct: Boolean = false,
    recipientNonces: Map<String, ByteArray> = emptyMap(),
    bodyNonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(authoritySecretKey.size == 32)
    val room = requireEpochHex(roomId, "room id")
    require(next.epoch == current.epoch + 1) { "a rekey moves the room forward by exactly one epoch" }
    val canonicalRemoved = removed.map { requireEpochHex(it, "removed participant") }.distinct().sorted()
    require(!scheduled || canonicalRemoved.isEmpty() && !closed) { "a scheduled rekey removes nobody and does not close the room" }
    require(!destruct || closed) { "only a closing rekey can make the room self-destruct" }
    val sealed = buildJsonObject { put("v", 1); put("secret", base64UrlEncode(next.secret)) }.toString()
    val keys = buildJsonObject {
        if (!closed) recipients.forEach { raw ->
            val device = requireEpochHex(raw, "recipient device")
            val key = Nip44.conversationKey(authoritySecretKey, device.hexToBytes())
            try {
                put(device, Nip44.encrypt(sealed, key, recipientNonces[device] ?: Entropy.bytes(32)))
            } finally { key.fill(0) }
        }
    }
    val body = buildJsonObject {
        put("v", 1); put("epoch", next.epoch); put("removed", strings(canonicalRemoved))
        if (by != null) put("by", requireEpochHex(by, "admin"))
        if (closed) put("closed", true)
        if (destruct) put("destruct", true)
        if (scheduled) put("scheduled", true)
        if (commit) put("commit", epochCommitment(room, next.epoch, next.secret))
        if (members != null) put("members", strings(members.map { requireEpochHex(it, "member participant") }.distinct().filter { it !in canonicalRemoved }.sorted()))
        put("keys", keys)
    }
    return Events.sign(
        authoritySecretKey, KIND_ROOM_REKEY, now,
        listOf(listOf("d", room), listOf("epoch", next.epoch.toString())),
        Nip44.encrypt(body.toString(), current.key, bodyNonce), auxRand,
    )
}

fun decodeRekeyEvent(
    event: NostrEvent,
    roomId: String,
    authority: String,
    current: EpochKeys,
    deviceSecretKey: ByteArray,
): RekeyNotice? = runCatching {
    require(deviceSecretKey.size == 32)
    val epoch = peekRekeyEpoch(event, roomId, authority) ?: return null
    if (epoch != current.epoch + 1) return null
    val body = Json.parseToJsonElement(Nip44.decrypt(event.content, current.key)).jsonObject
    if (body["v"]?.jsonPrimitive?.intOrNull != 1 || body["epoch"]?.jsonPrimitive?.intOrNull != epoch) return null
    val removedRaw = body["removed"] as? JsonArray ?: return null
    val removed = removedRaw.map { it.jsonPrimitive.content }.takeIf { it.all(HEX64::matches) }
        ?.map(String::normaliseHex)?.distinct()?.sorted() ?: return null
    val keys = body["keys"] as? JsonObject ?: return null
    val closed = body["closed"]?.jsonPrimitive?.booleanOrNull == true
    val by = body["by"]?.jsonPrimitive?.content?.takeIf(HEX64::matches)?.normaliseHex()
    val commit = (body["commit"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(HEX64::matches)?.normaliseHex()
    var secret: ByteArray? = null
    val device = Schnorr.publicKeyHex(deviceSecretKey)
    val mine = keys.entries.firstOrNull { it.key.hexEquals(device) }?.value?.jsonPrimitive?.content
    if (mine != null) {
        val conversation = Nip44.conversationKey(deviceSecretKey, event.pubkey.hexToBytes())
        try {
            val sealed = Json.parseToJsonElement(Nip44.decrypt(mine, conversation)).jsonObject
            if (sealed["v"]?.jsonPrimitive?.intOrNull == 1) {
                secret = sealed["secret"]?.jsonPrimitive?.content?.let(::base64UrlDecode)?.takeIf { it.size == 32 }
            }
        } finally { conversation.fill(0) }
    }
    // Believed only beside no removal and no close: a body that contradicts itself is still announced.
    val scheduled = body.isScheduled() && removed.isEmpty() && !closed
    try {
        RekeyNotice(epoch, removed, by, closed, secret, event.createdAt, commit = commit, members = readMemberList(body["members"]), scheduled = scheduled,
            destruct = closed && body.isDestruct())
    } finally { secret?.fill(0) }
}.getOrNull()

/**
 * The epoch commitment: what lets a member who is not the authority hand an epoch on, and the
 * requester check it without trusting that member.
 *
 * A rekey already commits to the secret of the epoch it LEAVES (its body is NIP-44 under that
 * epoch's key, signed by the authority, and NIP-44's MAC verifies under no other key). What it
 * does not commit to, in a form a third party can check, is the secret of the epoch it ENTERS.
 * This closes that gap: `HMAC-SHA256(key = secret, "kithmoot/v1/epoch-commit:" + roomId + ":" +
 * epoch)` as lower-case hex, the room id lower-case hex and the epoch decimal. It rides inside
 * the encrypted rekey body and is used nowhere else on the wire.
 */
fun epochCommitment(roomId: String, epoch: Int, secret: ByteArray): String {
    val room = requireEpochHex(roomId, "room id")
    require(epoch in 1..MAX_EPOCH) { "only an epoch after 0 has a commitment" }
    require(secret.size == 32) { "epoch secret must be 32 bytes" }
    return Digests.hmacSha256(secret, "$EPOCH_COMMIT_PREFIX$room:$epoch".toByteArray(Charsets.UTF_8)).toHex()
}

/** What a rekey body says, read with the key of the epoch it leaves. */
data class RekeyEvidence(
    val epoch: Int,
    val removed: List<String>,
    val closed: Boolean,
    /** The epoch commitment, when the authority wrote one. */
    val commit: String? = null,
    /** The authority's member list (#207), when it wrote one. */
    val members: List<String>? = null,
    /** A scheduled turn of the key: see [RekeyNotice.scheduled]. */
    val scheduled: Boolean = false,
    /** The closure self-destructs the room: see [RekeyNotice.destruct]. */
    val destruct: Boolean = false,
)

/**
 * Read an authority-signed rekey with the key of the epoch it leaves, needing no seal of one's
 * own. Null for anything that does not check out: not the authority, not this room, not epoch
 * [previousEpoch] + 1, or not encrypted under [previousKey]. Both sides of a member grant use it
 * to check the chain.
 */
fun readRekeyEvidence(event: NostrEvent, roomId: String, authority: String, previousEpoch: Int, previousKey: ByteArray): RekeyEvidence? = runCatching {
    val epoch = peekRekeyEpoch(event, roomId, authority) ?: return null
    if (epoch != previousEpoch + 1) return null
    val body = Json.parseToJsonElement(Nip44.decrypt(event.content, previousKey)).jsonObject
    if (body["v"].exactInt() != 1 || body["epoch"].exactInt() != epoch) return null
    val removedRaw = body["removed"] as? JsonArray ?: return null
    val removed = removedRaw.map { (it as JsonPrimitive).takeIf { p -> p.isString }?.content ?: return null }
        .takeIf { it.all(HEX64::matches) }?.map(String::normaliseHex)?.distinct()?.sorted() ?: return null
    val closed = (body["closed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
    val commit = (body["commit"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(HEX64::matches)?.normaliseHex()
    RekeyEvidence(epoch, removed, closed, commit, readMemberList(body["members"]), body.isScheduled() && removed.isEmpty() && !closed,
        destruct = closed && body.isDestruct())
}.getOrNull()

/** A JSON number that is an integer, as `Number.isSafeInteger` would see it, as a Long; null otherwise. */
internal fun kotlinx.serialization.json.JsonElement?.exactLong(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toLongOrNull()?.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }
}

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** Exactly `"scheduled": true`, as fold-kit's `body.scheduled === true` reads it. */
private fun JsonObject.isScheduled(): Boolean = (this["scheduled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

/** Exactly `"destruct": true`, as fold-kit's `body.destruct === true` reads it. */
private fun JsonObject.isDestruct(): Boolean = (this["destruct"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

/** A JSON number that is an integer, as `Number.isSafeInteger` would see it; null otherwise. */
internal fun kotlinx.serialization.json.JsonElement?.exactInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toIntOrNull()
}

/**
 * The key an epoch request's admission proof is computed under: the EPOCH-0 room key,
 * `deriveRoom(secret).roomKey`, expanded under its own info string. Epoch 0's on purpose:
 * the device asking is the one that has fallen behind, and epoch 0 is the one key every
 * admitted device holds however far behind it is.
 */
fun deriveEpochRequestKey(roomKey: ByteArray): ByteArray {
    require(roomKey.size == 32) { "a room key is 32 bytes" }
    return Digests.hkdfSha256(roomKey, null, EPOCH_REQUEST_KEY_INFO.toByteArray(Charsets.UTF_8), 32)
}

/**
 * Proof, inside an epoch request, that the asking device was admitted to the room.
 *
 * `HMAC-SHA256(deriveEpochRequestKey(roomKey), "kithmoot/v1/epoch-request:" + roomId + ":" +
 * authority + ":" + device + ":" + createdAt)` as lower-case hex, the identifiers lower-case
 * hex. The room id and the authority's pubkey are public on every rekey and a credential is
 * minted by any participant key, so without this a stranger reading the relay could be
 * handed an open room's current epoch. Bound to the device and the event's own `created_at`
 * so a proof lifted from one request is no use in another.
 */
fun epochRequestAdmission(roomKey: ByteArray, roomId: String, authority: String, device: String, createdAt: Long): String {
    require(createdAt >= 0) { "created_at must be a non-negative integer" }
    val message = EPOCH_REQUEST_MESSAGE + requireEpochHex(roomId, "room id") + ":" + requireEpochHex(authority, "authority pubkey") +
        ":" + requireEpochHex(device, "device pubkey") + ":" + createdAt
    val key = deriveEpochRequestKey(roomKey)
    return try {
        Digests.hmacSha256(key, message.toByteArray(Charsets.UTF_8)).toHex()
    } finally { key.fill(0) }
}

fun encodeEpochRequest(
    roomId: String,
    authority: String,
    /** The epoch-0 room key, which proves this device was admitted. */
    roomKey: ByteArray,
    deviceSecretKey: ByteArray,
    credential: NostrEvent,
    now: Long,
    proof: KindredProof? = null,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
    /** A conference room's end; see [withRoomExpiration]. */
    roomEnds: Long? = null,
): NostrEvent {
    require(deviceSecretKey.size == 32)
    val room = requireEpochHex(roomId, "room id")
    val peer = requireEpochHex(authority, "authority pubkey")
    val admission = epochRequestAdmission(roomKey, room, peer, Schnorr.publicKeyHex(deviceSecretKey), now)
    val body = buildJsonObject {
        put("v", 1)
        put("credential", credential.toJson())
        if (proof != null) put("proof", proof.toJson())
        put("admission", admission)
    }
    val key = Nip44.conversationKey(deviceSecretKey, peer.hexToBytes())
    return try {
        Events.sign(deviceSecretKey, KIND_EPOCH_REQUEST, now, withRoomExpiration(listOf(listOf("d", room), listOf("p", peer)), roomEnds), Nip44.encrypt(body.toString(), key, nonce), auxRand)
    } finally { key.fill(0) }
}

/**
 * Null for anything malformed, stale, misaddressed, from a device that cannot prove which
 * participant it speaks for in this room, or from one that cannot prove it was admitted to
 * the room at all. A request refused here must not be answered, so a stranger learns nothing.
 */
fun decodeEpochRequest(
    event: NostrEvent,
    roomId: String,
    authoritySecretKey: ByteArray,
    /** The epoch-0 room key the desk checks admission proofs against. */
    roomKey: ByteArray,
    now: Long,
    policy: RoomPolicy? = null,
    maxAgeSeconds: Long = EPOCH_MAX_AGE_SECONDS,
): EpochRequest? = decodeVerifiedEpochRequest(event, roomId, authoritySecretKey, roomKey, now, policy, maxAgeSeconds)?.request

/** The same verified boundary as [decodeEpochRequest], retaining the exact
 * room-bound credential for durable source-owned device enrolment. It grants
 * neither participant approval nor authority to answer an epoch request. */
fun decodeVerifiedEpochRequest(
    event: NostrEvent,
    roomId: String,
    authoritySecretKey: ByteArray,
    roomKey: ByteArray,
    now: Long,
    policy: RoomPolicy? = null,
    maxAgeSeconds: Long = EPOCH_MAX_AGE_SECONDS,
): VerifiedEpochRequest? = runCatching {
    require(authoritySecretKey.size == 32)
    if (event.kind != KIND_EPOCH_REQUEST || !Events.verify(event) || !epochFresh(event.createdAt, now, maxAgeSeconds)) return null
    val room = requireEpochHex(roomId, "room id")
    val authority = Schnorr.publicKeyHex(authoritySecretKey)
    if (event.tagValue("d")?.hexEquals(room) != true || event.tagValue("p")?.hexEquals(authority) != true) return null
    val key = Nip44.conversationKey(authoritySecretKey, event.pubkey.hexToBytes())
    val body = try { Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject } finally { key.fill(0) }
    if (body["v"]?.jsonPrimitive?.intOrNull != 1) return null
    val credential = (body["credential"] as? JsonObject)?.let(NostrEvent::fromJson) ?: return null
    val verdict = verifyDeviceCredential(credential, room, now) as? CredentialCheck.Valid ?: return null
    if (!verdict.device.hexEquals(event.pubkey)) return null
    // Admission before policy: a stranger with no room key is turned away
    // before anything about the room's tiers is consulted.
    val presented = body["admission"]?.jsonPrimitive?.content?.takeIf { HEX64.matches(it) } ?: return null
    val expected = epochRequestAdmission(roomKey, room, authority, verdict.device, event.createdAt)
    if (!MessageDigest.isEqual(presented.hexToBytes(), expected.hexToBytes())) return null
    if (policy != null) {
        val proof = (body["proof"] as? JsonObject)?.let(KindredProof::fromJson)
        if (!evaluateAccess(policy, verdict.participant, proof, now, room).admitted) return null
    }
    VerifiedEpochRequest(EpochRequest(verdict.device, verdict.participant, event.id), credential, now)
}.getOrNull()

fun encodeEpochGrant(
    roomId: String,
    authoritySecretKey: ByteArray,
    device: String,
    request: String,
    now: Long,
    epoch: RoomEpoch? = null,
    removed: List<String> = emptyList(),
    refused: String? = null,
    /** The participants the room knows (#207), so the requester's own member desk knows them. */
    members: List<String>? = null,
    /**
     * The epochs the room has left that are still read ([epochsInWindow]), written as `passed`
     * after `members`: each after epoch 0 and before [epoch], each once, at most
     * [MAX_HISTORY_EPOCHS]. Empty, the body is as before. This client's own desk never sends
     * any (rooms it is the authority of never leave epoch 0); it is here so the vectors rebuild.
     */
    passed: List<LeftEpoch> = emptyList(),
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
    /** A conference room's end; see [withRoomExpiration]. */
    roomEnds: Long? = null,
): NostrEvent {
    require(authoritySecretKey.size == 32)
    val room = requireEpochHex(roomId, "room id")
    val recipient = requireEpochHex(device, "device pubkey")
    val requestId = requireEpochHex(request, "request id")
    require(refused == null || refused in EPOCH_REFUSALS)
    require((refused == null) == (epoch != null)) { "a grant carries an epoch or a refusal" }
    val body = buildJsonObject {
        put("v", 1); put("request", requestId)
        if (refused != null) put("refused", refused) else {
            val current = requireNotNull(epoch)
            put("epoch", current.epoch)
            if (current.epoch > 0) put("secret", base64UrlEncode(current.secret))
            put("removed", buildJsonArray {
                removed.map { requireEpochHex(it, "removed participant") }.distinct().sorted().forEach { add(JsonPrimitive(it)) }
            })
            if (members != null) {
                val gone = removed.map(String::normaliseHex).toSet()
                put("members", strings(members.map { requireEpochHex(it, "member participant") }.distinct().filter { it !in gone }.sorted()))
            }
            if (passed.isNotEmpty()) put("passed", passedList(passed, current.epoch))
        }
    }
    val key = Nip44.conversationKey(authoritySecretKey, recipient.hexToBytes())
    return try {
        Events.sign(authoritySecretKey, KIND_EPOCH_GRANT, now, withRoomExpiration(listOf(listOf("d", room), listOf("p", recipient)), roomEnds), Nip44.encrypt(body.toString(), key, nonce), auxRand)
    } finally { key.fill(0) }
}

fun decodeEpochGrant(
    event: NostrEvent,
    roomId: String,
    authority: String,
    deviceSecretKey: ByteArray,
    request: String,
    now: Long,
    maxAgeSeconds: Long = EPOCH_MAX_AGE_SECONDS,
): EpochGrant? = runCatching {
    require(deviceSecretKey.size == 32)
    if (event.kind != KIND_EPOCH_GRANT || !event.pubkey.hexEquals(authority) || !Events.verify(event) || !epochFresh(event.createdAt, now, maxAgeSeconds)) return null
    val room = requireEpochHex(roomId, "room id")
    val device = Schnorr.publicKeyHex(deviceSecretKey)
    if (event.tagValue("d")?.hexEquals(room) != true || event.tagValue("p")?.hexEquals(device) != true) return null
    val key = Nip44.conversationKey(deviceSecretKey, event.pubkey.hexToBytes())
    val body = try { Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject } finally { key.fill(0) }
    if (body["v"]?.jsonPrimitive?.intOrNull != 1 || body["request"]?.jsonPrimitive?.content?.hexEquals(request) != true) return null
    body["refused"]?.jsonPrimitive?.content?.let { if (it in EPOCH_REFUSALS) return EpochGrant.Refused(it) }
    val epoch = body["epoch"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..MAX_EPOCH } ?: return null
    val removed = (body["removed"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.content.takeIf(HEX64::matches) }
        .map(String::normaliseHex).distinct().sorted()
    val members = readMemberList(body["members"])
    if (epoch == 0) return EpochGrant.Current(0, null, removed, members)
    val secret = body["secret"]?.jsonPrimitive?.content?.let(::base64UrlDecode)?.takeIf { it.size == 32 } ?: return null
    val passed = readPassedList(body["passed"], epoch)
    try {
        EpochGrant.Current(epoch, secret, removed, members, passed.orEmpty())
    } finally {
        secret.fill(0)
        passed?.forEach { it.secret.fill(0) }
    }
}.getOrNull()

private fun passedList(passed: List<LeftEpoch>, granted: Int) = buildJsonArray {
    require(passed.size <= MAX_HISTORY_EPOCHS) { "a grant carries at most $MAX_HISTORY_EPOCHS passed epochs" }
    val sorted = passed.sortedBy { it.epoch }
    require(sorted.all { it.epoch in 1 until granted }) { "a passed epoch comes after epoch 0 and before the one granted" }
    require(sorted.all { it.leftAt >= 0 }) { "leftAt must be a non-negative integer" }
    require(sorted.zipWithNext().none { (a, b) -> a.epoch == b.epoch }) { "a passed epoch is listed once" }
    sorted.forEach { add(buildJsonObject { put("epoch", it.epoch); put("secret", base64UrlEncode(it.secret)); put("left", it.leftAt) }) }
}

/**
 * A grant's passed epochs, or null unless every entry is in the form [encodeEpochGrant] writes:
 * strictly increasing, each in `[1, granted)`, at most [MAX_HISTORY_EPOCHS], 32-byte unpadded
 * base64url secrets and a non-negative integer `left`. One bad entry costs the history, never
 * the grant: the current epoch still stands. fold-kit's `readPassedList`.
 */
private fun readPassedList(raw: kotlinx.serialization.json.JsonElement?, granted: Int): List<LeftEpoch>? {
    val list = raw as? JsonArray ?: return null
    if (list.isEmpty() || list.size > MAX_HISTORY_EPOCHS) return null
    val out = mutableListOf<LeftEpoch>()
    try {
        for (entry in list) {
            val value = entry as? JsonObject ?: return null
            val epoch = value["epoch"].exactInt()?.takeIf { it in 1 until granted } ?: return null
            if (out.isNotEmpty() && epoch <= out.last().epoch) return null
            val left = value["left"].exactLong()?.takeIf { it >= 0 } ?: return null
            val text = (value["secret"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            // Unpadded, as `base64urlnopad` insists; Java's decoder would take the padding too.
            if ('=' in text) return null
            val secret = runCatching { base64UrlDecode(text) }.getOrNull() ?: return null
            try {
                if (secret.size != 32) return null
                out += LeftEpoch(epoch, secret, left)
            } finally { secret.fill(0) }
        }
        return out.toList().also { out.clear() }
    } finally { out.forEach { it.secret.fill(0) } }
}

fun canonicalAdmins(admins: List<String>): List<String> = admins.map { requireEpochHex(it, "admin pubkey") }.distinct().sorted()

fun signAdmins(roomId: String, epoch: Int, admins: List<String>, authoritySecretKey: ByteArray, auxRand: ByteArray = Entropy.bytes(32)): String {
    require(authoritySecretKey.size == 32)
    return Schnorr.sign(adminsMessage(requireEpochHex(roomId, "room id"), requireEpoch(epoch), canonicalAdmins(admins)), authoritySecretKey, auxRand).toHex()
}

fun verifyAdmins(roomId: String, epoch: Int, admins: List<String>, signature: String, authority: String): Boolean = runCatching {
    Schnorr.verify(signature.hexToBytes(), adminsMessage(requireEpochHex(roomId, "room id"), requireEpoch(epoch), canonicalAdmins(admins)), requireEpochHex(authority, "authority").hexToBytes())
}.getOrDefault(false)

private fun adminsMessage(roomId: String, epoch: Int, admins: List<String>) =
    Digests.sha256("kithmoot/v1/admins:$roomId:$epoch:${admins.joinToString(",")}".toByteArray(Charsets.UTF_8))

private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

internal fun requireEpochHex(value: String, name: String): String = value.also { require(HEX64.matches(it)) { "$name must be 32-byte hex" } }.normaliseHex()
private fun requireEpoch(value: Int): Int = value.also { require(it in 0..MAX_EPOCH) { "epoch must be a small non-negative integer" } }
internal fun epochFresh(createdAt: Long, now: Long, maxAge: Long): Boolean {
    if (createdAt < 0 || now < 0 || maxAge < 0) return false
    return if (createdAt >= now) createdAt - now <= maxAge else now - createdAt <= maxAge
}
