package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The call bell: kind 1464, one event when a call starts and one when it
 * ends, so a phone with the app closed can wait on an idle socket for the
 * one thing that should wake it, instead of receiving and decrypting every
 * roster heartbeat in every room. See "Call bell" in `docs/protocol.md` and
 * `call-bell.ts` in the web client, which this mirrors byte for byte.
 *
 * The outer event is signed by a key minted for that one bell and thrown
 * away, never a device or a participant key. Its only tags are `d`, a
 * rendezvous derived from the room's current epoch key and the UTC day, and
 * a NIP-40 `expiration`. The content is NIP-44 v2 under a key derived from
 * the same epoch key, and carries the device that rang and that device's
 * signature, so a room member can attribute it and nobody else can.
 */
const val KIND_CALL_BELL: Int = 1464

/** How long a bell means anything, in seconds: its NIP-40 expiration, and
 *  the oldest a reader accepts. */
const val CALL_BELL_TTL_SECONDS: Long = 120
/** How far ahead of the reader's clock a bell may be stamped. */
const val CALL_BELL_FUTURE_SKEW_SECONDS: Long = 60
/** How long a call may have been running when its `end` rings. */
const val CALL_BELL_MAX_CALL_SECONDS: Long = 30 * 86_400

private const val TAG_KEY_INFO = "kithmoot/v1/call-bell-tag"
private const val CONTENT_KEY_INFO = "kithmoot/v1/call-bell-key"
private const val TAG_PREFIX = "kithmoot-call-bell-v1|"
private const val SIGNATURE_PREFIX = "kithmoot/v1/call-bell"

enum class CallBellState(val wire: String) {
    START("start"),
    END("end"),
    ;

    companion object {
        fun from(value: String?): CallBellState? = entries.find { it.wire == value }
    }
}

/** A decoded, verified bell. */
data class CallBell(
    val state: CallBellState,
    val call: CallMembership,
    /** The device that rang. A reader attributes it through the roster. */
    val device: String,
    /** The outer event's `created_at`, which the device's signature covers. */
    val createdAt: Long,
)

/** The UTC day of a Unix second, `yyyy-mm-dd`. */
fun callBellDay(unixSeconds: Long): String {
    val instant = java.time.Instant.ofEpochSecond(unixSeconds)
    return java.time.LocalDate.ofInstant(instant, java.time.ZoneOffset.UTC).toString()
}

/**
 * The bell's `d` tag for a room/epoch key at a moment:
 * `hex(HMAC-SHA256(HKDF(key, "kithmoot/v1/call-bell-tag"), "kithmoot-call-bell-v1|" + day))`,
 * first 32 hex characters. It changes at every UTC midnight and shares
 * nothing with the roster's `d`.
 */
fun callBellTag(key: ByteArray, unixSeconds: Long): String {
    val tagKey = Digests.hkdfSha256(key, null, TAG_KEY_INFO.toByteArray(Charsets.UTF_8), 32)
    val message = (TAG_PREFIX + callBellDay(unixSeconds)).toByteArray(Charsets.UTF_8)
    return Digests.hmacSha256(tagKey, message).toHex().substring(0, 32)
}

/** The tags a listener subscribes to at [now]: today's, and the neighbouring
 *  day's while a bell stamped on the other side of midnight could still be
 *  accepted. One, two or three distinct values. */
fun callBellListenTags(key: ByteArray, now: Long): List<String> =
    listOf(now - CALL_BELL_TTL_SECONDS, now, now + CALL_BELL_FUTURE_SKEW_SECONDS)
        .map { callBellTag(key, it) }
        .distinct()

/** The NIP-44 conversation key the bell's content is sealed under. */
fun callBellContentKey(key: ByteArray): ByteArray =
    Digests.hkdfSha256(key, null, CONTENT_KEY_INFO.toByteArray(Charsets.UTF_8), 32)

/** The 32 bytes the device signs:
 *  `sha256("kithmoot/v1/call-bell:" + room + ":" + state + ":" + callId + ":" + since + ":" + createdAt)`. */
fun callBellMessage(roomId: String, state: CallBellState, call: CallMembership, createdAt: Long): ByteArray {
    val text = "$SIGNATURE_PREFIX:${roomId.lowercase()}:${state.wire}:${call.id}:${call.since}:$createdAt"
    return Digests.sha256(text.toByteArray(Charsets.UTF_8))
}

/** Everything needed to ring one bell. */
data class EncodeCallBellOptions(
    val roomId: String,
    /** The current room/epoch key: the one the roster rides under. */
    val key: ByteArray,
    val deviceSecretKey: ByteArray,
    val state: CallBellState,
    val call: CallMembership,
    /** Unix seconds. */
    val createdAt: Long,
    val throwawaySecretKey: ByteArray = Entropy.bytes(32),
    val nonce: ByteArray = Entropy.bytes(32),
    val deviceAuxRand: ByteArray = Entropy.bytes(32),
    val throwawayAuxRand: ByteArray = Entropy.bytes(32),
)

/** Build a bell, signed by a key minted here and discarded by the caller. */
fun encodeCallBellEvent(opts: EncodeCallBellOptions): NostrEvent {
    val createdAt = opts.createdAt
    val call = opts.call.copy(id = opts.call.id.lowercase())
    val sig = Schnorr.sign(callBellMessage(opts.roomId, opts.state, call, createdAt), opts.deviceSecretKey, opts.deviceAuxRand).toHex()
    val plaintext = buildJsonObject {
        put("v", 1)
        put("state", opts.state.wire)
        put("call", call.toJson())
        put("device", Schnorr.publicKeyHex(opts.deviceSecretKey))
        put("sig", sig)
    }.toString()
    val tags = listOf(
        listOf("d", callBellTag(opts.key, createdAt)),
        listOf("expiration", (createdAt + CALL_BELL_TTL_SECONDS).toString()),
    )
    val content = Nip44.encrypt(plaintext, callBellContentKey(opts.key), opts.nonce)
    return Events.sign(
        secretKey = opts.throwawaySecretKey,
        kind = KIND_CALL_BELL,
        createdAt = createdAt,
        tags = tags,
        content = content,
        auxRand = opts.throwawayAuxRand,
    )
}

private val HEX64 = Regex("^[0-9a-f]{64}$")
private val HEX128 = Regex("^[0-9a-f]{128}$")
private val CALL_ID = Regex("^[0-9a-f]{32}$")

/**
 * Read and verify a bell. Null for anything that does not check out: wrong
 * kind or tag, bad outer signature, a key that does not open it, an unknown
 * version or state, a malformed call, a device signature that fails, a bell
 * older than its expiry or stamped too far ahead, a `since` that cannot be
 * right. Never throws. Whether `device` belongs to the room is the caller's
 * to check against the roster.
 */
fun decodeCallBellEvent(event: NostrEvent, roomId: String, key: ByteArray, now: Long): CallBell? = try {
    val createdAt = event.createdAt
    when {
        event.kind != KIND_CALL_BELL -> null
        now - createdAt > CALL_BELL_TTL_SECONDS -> null
        createdAt - now > CALL_BELL_FUTURE_SKEW_SECONDS -> null
        else -> {
            val d = event.tagValue("d")
            if (d == null || !d.lowercase().equals(callBellTag(key, createdAt), ignoreCase = true)) {
                null
            } else if (!Events.verify(event)) {
                null
            } else {
                val body = kotlinx.serialization.json.Json
                    .parseToJsonElement(Nip44.decrypt(event.content, callBellContentKey(key)))
                    .jsonObject
                decodeBellBody(body, roomId, createdAt)
            }
        }
    }
} catch (_: Exception) {
    null
}

private fun decodeBellBody(body: JsonObject, roomId: String, createdAt: Long): CallBell? {
    val v = (body["v"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
    if (v != 1) return null
    val state = CallBellState.from((body["state"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content) ?: return null
    val rawCall = body["call"] as? JsonObject ?: return null
    val id = (rawCall["id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!CALL_ID.matches(id)) return null
    val sincePrimitive = rawCall["since"] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    if (sincePrimitive.isString) return null
    val since = sincePrimitive.content.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val call = CallMembership(id, kotlin.math.floor(since).toLong())
    if (call.since > createdAt + CALL_BELL_FUTURE_SKEW_SECONDS) return null
    val oldest = if (state == CallBellState.START) CALL_BELL_TTL_SECONDS else CALL_BELL_MAX_CALL_SECONDS
    if (call.since < createdAt - oldest) return null
    val device = (body["device"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!HEX64.matches(device.lowercase())) return null
    val sig = (body["sig"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!HEX128.matches(sig.lowercase())) return null
    val deviceLower = device.lowercase()
    val ok = Schnorr.verify(
        sig.lowercase().hexToBytes(),
        callBellMessage(roomId, state, call, createdAt),
        deviceLower.hexToBytes(),
    )
    if (!ok) return null
    return CallBell(state, call, deviceLower, createdAt)
}
