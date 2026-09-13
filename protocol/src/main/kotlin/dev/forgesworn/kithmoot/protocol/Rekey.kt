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

const val KIND_ROOM_REKEY = 1462
const val KIND_EPOCH_REQUEST = 20_468
const val KIND_EPOCH_GRANT = 20_469
const val MAX_EPOCH = 1_000_000
const val EPOCH_MAX_AGE_SECONDS = 90L

private const val EPOCH_ID_INFO = "kithmoot/v1/epoch-id"
private const val EPOCH_KEY_INFO = "kithmoot/v1/epoch-key"
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
) {
    val removed = removed.toList()
    val secret = secret?.copyOf()
}

data class EpochRequest(val device: String, val participant: String, val request: String)

sealed interface EpochGrant {
    class Current(val epoch: Int, secret: ByteArray?, removed: List<String>) : EpochGrant {
        val secret = secret?.copyOf()
        val removed = removed.toList()
    }
    data class Refused(val reason: String) : EpochGrant
}

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
    if (event.tagValue("d")?.hexEquals(requireHex(roomId, "room id")) != true) return null
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
    recipientNonces: Map<String, ByteArray> = emptyMap(),
    bodyNonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(authoritySecretKey.size == 32)
    val room = requireHex(roomId, "room id")
    require(next.epoch == current.epoch + 1) { "a rekey moves the room forward by exactly one epoch" }
    val canonicalRemoved = removed.map { requireHex(it, "removed participant") }.distinct().sorted()
    val sealed = buildJsonObject { put("v", 1); put("secret", base64UrlEncode(next.secret)) }.toString()
    val keys = buildJsonObject {
        if (!closed) recipients.forEach { raw ->
            val device = requireHex(raw, "recipient device")
            val key = Nip44.conversationKey(authoritySecretKey, device.hexToBytes())
            try {
                put(device, Nip44.encrypt(sealed, key, recipientNonces[device] ?: Entropy.bytes(32)))
            } finally { key.fill(0) }
        }
    }
    val body = buildJsonObject {
        put("v", 1); put("epoch", next.epoch); put("removed", strings(canonicalRemoved))
        if (by != null) put("by", requireHex(by, "admin"))
        if (closed) put("closed", true)
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
    try {
        RekeyNotice(epoch, removed, by, closed, secret, event.createdAt)
    } finally { secret?.fill(0) }
}.getOrNull()

fun encodeEpochRequest(
    roomId: String,
    authority: String,
    deviceSecretKey: ByteArray,
    credential: NostrEvent,
    now: Long,
    proof: KindredProof? = null,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(deviceSecretKey.size == 32)
    val room = requireHex(roomId, "room id")
    val peer = requireHex(authority, "authority pubkey")
    val body = buildJsonObject {
        put("v", 1)
        put("credential", credential.toJson())
        if (proof != null) put("proof", proof.toJson())
    }
    val key = Nip44.conversationKey(deviceSecretKey, peer.hexToBytes())
    return try {
        Events.sign(deviceSecretKey, KIND_EPOCH_REQUEST, now, listOf(listOf("d", room), listOf("p", peer)), Nip44.encrypt(body.toString(), key, nonce), auxRand)
    } finally { key.fill(0) }
}

fun decodeEpochRequest(
    event: NostrEvent,
    roomId: String,
    authoritySecretKey: ByteArray,
    now: Long,
    policy: RoomPolicy? = null,
    maxAgeSeconds: Long = EPOCH_MAX_AGE_SECONDS,
): EpochRequest? = runCatching {
    require(authoritySecretKey.size == 32)
    if (event.kind != KIND_EPOCH_REQUEST || !Events.verify(event) || !fresh(event.createdAt, now, maxAgeSeconds)) return null
    val room = requireHex(roomId, "room id")
    val authority = Schnorr.publicKeyHex(authoritySecretKey)
    if (event.tagValue("d")?.hexEquals(room) != true || event.tagValue("p")?.hexEquals(authority) != true) return null
    val key = Nip44.conversationKey(authoritySecretKey, event.pubkey.hexToBytes())
    val body = try { Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject } finally { key.fill(0) }
    if (body["v"]?.jsonPrimitive?.intOrNull != 1) return null
    val credential = (body["credential"] as? JsonObject)?.let(NostrEvent::fromJson) ?: return null
    val verdict = verifyDeviceCredential(credential, room, now) as? CredentialCheck.Valid ?: return null
    if (!verdict.device.hexEquals(event.pubkey)) return null
    if (policy != null) {
        val proof = (body["proof"] as? JsonObject)?.let(KindredProof::fromJson)
        if (!evaluateAccess(policy, verdict.participant, proof, now, room).admitted) return null
    }
    EpochRequest(verdict.device, verdict.participant, event.id)
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
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(authoritySecretKey.size == 32)
    val room = requireHex(roomId, "room id")
    val recipient = requireHex(device, "device pubkey")
    val requestId = requireHex(request, "request id")
    require(refused in setOf(null, "removed", "closed"))
    require((refused == null) == (epoch != null)) { "a grant carries an epoch or a refusal" }
    val body = buildJsonObject {
        put("v", 1); put("request", requestId)
        if (refused != null) put("refused", refused) else {
            val current = requireNotNull(epoch)
            put("epoch", current.epoch)
            if (current.epoch > 0) put("secret", base64UrlEncode(current.secret))
            put("removed", buildJsonArray {
                removed.map { requireHex(it, "removed participant") }.distinct().sorted().forEach { add(JsonPrimitive(it)) }
            })
        }
    }
    val key = Nip44.conversationKey(authoritySecretKey, recipient.hexToBytes())
    return try {
        Events.sign(authoritySecretKey, KIND_EPOCH_GRANT, now, listOf(listOf("d", room), listOf("p", recipient)), Nip44.encrypt(body.toString(), key, nonce), auxRand)
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
    if (event.kind != KIND_EPOCH_GRANT || !event.pubkey.hexEquals(authority) || !Events.verify(event) || !fresh(event.createdAt, now, maxAgeSeconds)) return null
    val room = requireHex(roomId, "room id")
    val device = Schnorr.publicKeyHex(deviceSecretKey)
    if (event.tagValue("d")?.hexEquals(room) != true || event.tagValue("p")?.hexEquals(device) != true) return null
    val key = Nip44.conversationKey(deviceSecretKey, event.pubkey.hexToBytes())
    val body = try { Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject } finally { key.fill(0) }
    if (body["v"]?.jsonPrimitive?.intOrNull != 1 || body["request"]?.jsonPrimitive?.content?.hexEquals(request) != true) return null
    body["refused"]?.jsonPrimitive?.content?.let { if (it in setOf("removed", "closed")) return EpochGrant.Refused(it) }
    val epoch = body["epoch"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..MAX_EPOCH } ?: return null
    val removed = (body["removed"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.content.takeIf(HEX64::matches) }
        .map(String::normaliseHex).distinct().sorted()
    if (epoch == 0) return EpochGrant.Current(0, null, removed)
    val secret = body["secret"]?.jsonPrimitive?.content?.let(::base64UrlDecode)?.takeIf { it.size == 32 } ?: return null
    try {
        EpochGrant.Current(epoch, secret, removed)
    } finally { secret.fill(0) }
}.getOrNull()

fun canonicalAdmins(admins: List<String>): List<String> = admins.map { requireHex(it, "admin pubkey") }.distinct().sorted()

fun signAdmins(roomId: String, epoch: Int, admins: List<String>, authoritySecretKey: ByteArray, auxRand: ByteArray = Entropy.bytes(32)): String {
    require(authoritySecretKey.size == 32)
    return Schnorr.sign(adminsMessage(requireHex(roomId, "room id"), requireEpoch(epoch), canonicalAdmins(admins)), authoritySecretKey, auxRand).toHex()
}

fun verifyAdmins(roomId: String, epoch: Int, admins: List<String>, signature: String, authority: String): Boolean = runCatching {
    Schnorr.verify(signature.hexToBytes(), adminsMessage(requireHex(roomId, "room id"), requireEpoch(epoch), canonicalAdmins(admins)), requireHex(authority, "authority").hexToBytes())
}.getOrDefault(false)

private fun adminsMessage(roomId: String, epoch: Int, admins: List<String>) =
    Digests.sha256("kithmoot/v1/admins:$roomId:$epoch:${admins.joinToString(",")}".toByteArray(Charsets.UTF_8))

private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

private fun requireHex(value: String, name: String): String = value.also { require(HEX64.matches(it)) { "$name must be 32-byte hex" } }.normaliseHex()
private fun requireEpoch(value: Int): Int = value.also { require(it in 0..MAX_EPOCH) { "epoch must be a small non-negative integer" } }
private fun fresh(createdAt: Long, now: Long, maxAge: Long): Boolean {
    if (createdAt < 0 || now < 0 || maxAge < 0) return false
    return if (createdAt >= now) createdAt - now <= maxAge else now - createdAt <= maxAge
}
