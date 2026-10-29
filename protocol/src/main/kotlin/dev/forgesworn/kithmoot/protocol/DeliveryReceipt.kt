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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Delivery receipts (kind 1465): a device saying "these messages reached
 * me", so their sender can show two ticks. Never "seen" or "read": a receipt
 * means the message was decrypted and verified on a device, and nothing
 * about whether anyone looked. See "Delivery receipts" in `docs/protocol.md`
 * and `delivery-receipt.ts` in the web client, which this mirrors byte for
 * byte, check for check, and in the same order: cheap bounds before any
 * signature check or decryption.
 *
 * The outer event is signed by a key made for that one receipt, derived from
 * the device key so the device can later ask relays to delete it. Its only
 * tags are the chat stream's `d` and a NIP-40 `expiration` at most seven days
 * out. The content is NIP-44 v2 under a key derived from the stream key and
 * carries the receiving device's signature and credential.
 *
 * This is the codec only: nothing here sends, stores or displays a receipt.
 */
const val KIND_DELIVERY_RECEIPT: Int = 1465

/** How long a receipt lives: its NIP-40 expiration at most, and the oldest a reader accepts. Seven days. */
const val DELIVERY_RECEIPT_TTL_SECONDS: Long = 604_800
/** How far ahead of the reader's clock a receipt may be stamped, and the slack on its expiration. */
const val DELIVERY_RECEIPT_FUTURE_SKEW_SECONDS: Long = 60
/** The most message hashes one receipt may carry. A writer splits beyond it. */
const val MAX_DELIVERY_RECEIPT_IDS: Int = 256
/** Maximum UTF-8 wire size, checked before signature verification or decryption. */
const val MAX_DELIVERY_RECEIPT_EVENT_BYTES: Int = 16_384
/** The most tags a receipt may carry: `d`, `expiration` and room to grow. */
const val MAX_DELIVERY_RECEIPT_TAGS: Int = 4
/** How many receipts per stream per second [ownDeliveryReceipts] looks for (indices 0 to 7). */
const val MAX_DELIVERY_RECEIPT_INDEX: Int = 8

const val DELIVERY_RECEIPT_SIGNATURE_PREFIX: String = "kithmoot/v1/delivery-receipt"
private const val OUTER_KEY_INFO = "kithmoot/v1/delivery-receipt-outer"
private const val RECEIPT_KEY_INFO = "kithmoot/v1/delivery-receipt-key"
private const val ID_HASH_PREFIX = "kithmoot/v1/delivery-receipt-id:"

private const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

private val HEX64 = Regex("^[0-9a-f]{64}$")
private val HEX64_ANY_CASE = Regex("^[0-9a-fA-F]{64}$")
private val HEX128_ANY_CASE = Regex("^[0-9a-fA-F]{128}$")
private val HEX128 = Regex("^[0-9a-f]{128}$")
private val ID_HASH = Regex("^[0-9a-f]{16}$")
private val DECIMAL = Regex("^(0|[1-9][0-9]*)$")

/**
 * One chat stream a reader or writer follows: its `d` tag and the stream key
 * its receipts' key is derived from (the same `d` and key chat uses, from
 * `deriveChatChannel`). Holds the key it is given; the caller owns erasure.
 */
class DeliveryReceiptStream(val d: String, val key: ByteArray)

/** A receipt that passed every check. */
data class DeliveryReceipt(
    /** The stream it was published under: which chat it is about. */
    val d: String,
    /** The outer event's `created_at`, which the device signature covers. */
    val createdAt: Long,
    /** The receiving person, bound to [device] by the carried credential. */
    val participant: String,
    val device: String,
    /** True only when the receipt said exactly `true`. */
    val agent: Boolean,
    /** [deliveryReceiptIdHash] of each message received. */
    val got: List<String>,
)

/** Everything needed to write one receipt. */
class EncodeDeliveryReceiptOptions(
    /** The room the credential names, whatever epoch the stream rides in. */
    val roomId: String,
    /** The chat stream these messages arrived on. */
    val stream: DeliveryReceiptStream,
    val deviceSecretKey: ByteArray,
    /** This device's credential for the room, valid at [createdAt]. Its participant is what the receipt names. */
    val credential: NostrEvent,
    /** The admission proof chat carries in closed tiers, when there is one. */
    val proof: KindredProof? = null,
    val agent: Boolean = false,
    /** The ids (`ChatMessage.id`) being acknowledged. Hashed here; duplicates collapse. One to [MAX_DELIVERY_RECEIPT_IDS]. */
    val messageIds: List<String>,
    /** Unix seconds. */
    val createdAt: Long,
    /** Which receipt this is within the same second for this stream, when a flush splits. */
    val index: Int = 0,
    /** A conference room's end, in unix seconds, which lowers the expiration. */
    val roomEnds: Long? = null,
    val nonce: ByteArray = Entropy.bytes(32),
    val deviceAuxRand: ByteArray = Entropy.bytes(32),
    val outerAuxRand: ByteArray = Entropy.bytes(32),
)

/** What a reader knows: its room, the streams it follows, the time, and the room's access policy if any. */
class DecodeDeliveryReceiptOptions(
    val roomId: String,
    /** Every stream this reader follows: a receipt under any other `d` is refused. */
    val streams: Collection<DeliveryReceiptStream>,
    /** Unix seconds. */
    val now: Long,
    val policy: RoomPolicy? = null,
)

/** A receipt this device wrote, with the outer key that signed it. The caller zeroes [secretKey]. */
class OwnDeliveryReceipt(val event: NostrEvent, val secretKey: ByteArray)

/**
 * The NIP-44 conversation key receipts on a stream are sealed under:
 * HKDF-SHA256(stream key, empty salt, `kithmoot/v1/delivery-receipt-key`).
 * Separate from the chat key, so a receipt can never be read as chat.
 */
fun deliveryReceiptKey(streamKey: ByteArray): ByteArray =
    Digests.hkdfSha256(streamKey, null, RECEIPT_KEY_INFO.toByteArray(Charsets.UTF_8), 32)

/**
 * The outer secret key of one receipt: HKDF-SHA256(device secret key, empty
 * salt, `kithmoot/v1/delivery-receipt-outer:<d>:<created_at>:<n>`). Derived,
 * not drawn, so the device can sign NIP-09 deletions of its own receipts.
 * Refused, never reduced, outside [1, n). The caller zeroes it.
 */
fun deliveryReceiptOuterSecretKey(deviceSecretKey: ByteArray, d: String, createdAt: Long, index: Int = 0): ByteArray {
    require(createdAt in 0..MAX_SAFE_INTEGER) { "a receipt time is a whole number of seconds" }
    require(index >= 0) { "a receipt index is a whole number" }
    val info = "$OUTER_KEY_INFO:${d.normaliseHex()}:$createdAt:$index"
    val secretKey = Digests.hkdfSha256(deviceSecretKey, null, info.toByteArray(Charsets.UTF_8), 32)
    try {
        Schnorr.publicKey(secretKey)
    } catch (e: Exception) {
        secretKey.fill(0)
        throw IllegalArgumentException("not a valid secret key", e)
    }
    return secretKey
}

/**
 * What a receipt says about one message: the first 16 hex characters of
 * SHA-256(UTF-8 `kithmoot/v1/delivery-receipt-id:` + message id).
 */
fun deliveryReceiptIdHash(messageId: String): String =
    Digests.sha256((ID_HASH_PREFIX + messageId).toByteArray(Charsets.UTF_8)).toHex().substring(0, 16)

/**
 * What the receiving device signs: SHA-256 of UTF-8
 * `kithmoot/v1/delivery-receipt:<room id>:<d>:<outer pubkey>:<created_at>:<body>`,
 * hex lower case.
 */
fun deliveryReceiptDigest(roomId: String, d: String, outer: String, createdAt: Long, body: String): ByteArray =
    Digests.sha256(
        "$DELIVERY_RECEIPT_SIGNATURE_PREFIX:${roomId.normaliseHex()}:${d.normaliseHex()}:${outer.normaliseHex()}:$createdAt:$body"
            .toByteArray(Charsets.UTF_8),
    )

/**
 * Encode one receipt. Throws [IllegalArgumentException] when the inputs could
 * not produce a receipt a reader would accept: no ids or too many, a
 * credential that does not name this device for this room at `createdAt`, or
 * an event over the size cap (split the ids and try again).
 */
fun encodeDeliveryReceipt(opts: EncodeDeliveryReceiptOptions): NostrEvent {
    val got = opts.messageIds.map(::deliveryReceiptIdHash).distinct()
    require(got.isNotEmpty() && got.size <= MAX_DELIVERY_RECEIPT_IDS) { "a receipt names 1 to 256 messages" }
    require(opts.createdAt in 0..MAX_SAFE_INTEGER) { "a receipt time is a whole number of seconds" }
    require(HEX64_ANY_CASE.matches(opts.stream.d)) { "a receipt rides a chat stream" }
    val roomId = opts.roomId.normaliseHex()
    val d = opts.stream.d.normaliseHex()
    val device = Schnorr.publicKeyHex(opts.deviceSecretKey)
    val verdict = verifyDeviceCredential(opts.credential, roomId, opts.createdAt)
    require(verdict is CredentialCheck.Valid && verdict.device.hexEquals(device)) {
        "a receipt carries this device's credential for the room"
    }
    val participant = verdict.participant.normaliseHex()

    // Canonical order, no whitespace, optional fields absent rather than null.
    val body = buildJsonObject {
        put("v", 1)
        put("room", roomId)
        put("participant", participant)
        put("device", device)
        put("credential", credentialJson(opts.credential))
        if (opts.proof != null) put("proof", opts.proof.toJson())
        if (opts.agent) put("agent", true)
        put("got", buildJsonArray { for (hash in got) add(JsonPrimitive(hash)) })
    }.toString()
    val tags = withRoomExpiration(
        listOf(
            listOf("d", d),
            listOf("expiration", (opts.createdAt + DELIVERY_RECEIPT_TTL_SECONDS).toString()),
        ),
        opts.roomEnds,
    )
    val outerSecretKey = deliveryReceiptOuterSecretKey(opts.deviceSecretKey, d, opts.createdAt, opts.index)
    val receiptKey = deliveryReceiptKey(opts.stream.key)
    try {
        val outer = Schnorr.publicKeyHex(outerSecretKey)
        val sig = Schnorr.sign(deliveryReceiptDigest(roomId, d, outer, opts.createdAt, body), opts.deviceSecretKey, opts.deviceAuxRand).toHex()
        val envelope = buildJsonObject {
            put("v", 1)
            put("body", body)
            put("sig", sig)
        }.toString()
        val event = Events.sign(
            secretKey = outerSecretKey,
            kind = KIND_DELIVERY_RECEIPT,
            createdAt = opts.createdAt,
            tags = tags,
            content = Nip44.encrypt(envelope, receiptKey, opts.nonce),
            auxRand = opts.outerAuxRand,
        )
        require(wireBytes(event) <= MAX_DELIVERY_RECEIPT_EVENT_BYTES) { "receipt too large; split its messages" }
        return event
    } finally {
        outerSecretKey.fill(0)
        receiptKey.fill(0)
    }
}

/**
 * Decode and verify a receipt. Null for anything that does not check out,
 * never throws: this runs inside a relay subscription handler. Cheap bounds
 * come before any signature check or decryption.
 */
fun decodeDeliveryReceipt(event: NostrEvent, opts: DecodeDeliveryReceiptOptions): DeliveryReceipt? {
    var receiptKey: ByteArray? = null
    try {
        val shape = boundedReceipt(event) ?: return null
        val createdAt = event.createdAt
        if (createdAt > opts.now + DELIVERY_RECEIPT_FUTURE_SKEW_SECONDS) return null
        if (createdAt < opts.now - DELIVERY_RECEIPT_TTL_SECONDS) return null
        if (shape.expiration <= opts.now) return null
        val stream = opts.streams.firstOrNull { it.d.hexEquals(shape.d) } ?: return null
        if (!Events.verify(event)) return null

        receiptKey = deliveryReceiptKey(stream.key)
        val envelope = openEnvelope(Json.parseToJsonElement(Nip44.decrypt(event.content, receiptKey))) ?: return null
        val body = Json.parseToJsonElement(envelope.body) as? JsonObject ?: return null
        if (!body.isNumber("v", 1)) return null
        val room = body.string("room") ?: return null
        if (!HEX64.matches(room) || !room.hexEquals(opts.roomId)) return null
        val participant = body.string("participant")?.takeIf { HEX64.matches(it) } ?: return null
        val device = body.string("device")?.takeIf { HEX64.matches(it) } ?: return null
        val credential = body["credential"] as? JsonObject ?: return null
        val proofJson = body["proof"]
        if (proofJson != null && proofJson !is JsonObject) return null
        val got = wellFormedGot(body["got"]) ?: return null

        val digest = deliveryReceiptDigest(opts.roomId, shape.d, event.pubkey, createdAt, envelope.body)
        if (!Schnorr.verify(envelope.sig.hexToBytes(), digest, device.hexToBytes())) return null

        // The credential is what binds the device to the person it names, as at
        // the receipt's own time, exactly as chat checks it at `sentAt`.
        val verdict = verifyDeviceCredential(NostrEvent.fromJson(credential), opts.roomId, createdAt)
        if (verdict !is CredentialCheck.Valid || !verdict.device.hexEquals(device) || !verdict.participant.hexEquals(participant)) return null
        if (opts.policy != null) {
            val proof = (proofJson as? JsonObject)?.let { runCatching { KindredProof.fromJson(it) }.getOrNull() }
            if (!evaluateAccess(opts.policy, participant, proof, createdAt, opts.roomId).admitted) return null
        }

        return DeliveryReceipt(
            d = shape.d.normaliseHex(),
            createdAt = createdAt,
            participant = participant,
            device = device,
            agent = (body["agent"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
            got = got,
        )
    } catch (_: Exception) {
        return null
    } finally {
        receiptKey?.fill(0)
    }
}

/**
 * The receipts among [events] that this device wrote, each with the outer key
 * that signed it, so the device can ask for their deletion (NIP-09 takes a
 * deletion only from the event's own author). A receipt is this device's when
 * its stream opens it, its body names this device, and the outer key derived
 * from this device's key, `d`, `created_at` and an index below
 * [MAX_DELIVERY_RECEIPT_INDEX] is its author: nobody else can sign with that
 * key, so the inner signature need not be checked. Never throws; the caller
 * zeroes each [OwnDeliveryReceipt.secretKey]. Mirrors `ownDeliveryReceipts`
 * in `delivery-receipt.ts`.
 */
fun ownDeliveryReceipts(
    events: Collection<NostrEvent>,
    streams: Collection<DeliveryReceiptStream>,
    deviceSecretKey: ByteArray,
): List<OwnDeliveryReceipt> {
    val device = try {
        Schnorr.publicKeyHex(deviceSecretKey)
    } catch (_: Exception) {
        return emptyList()
    }
    val out = ArrayList<OwnDeliveryReceipt>()
    for (event in events) {
        var receiptKey: ByteArray? = null
        try {
            val shape = boundedReceipt(event)
            if (shape == null || event.pubkey.hexEquals(device)) continue
            val stream = streams.firstOrNull { it.d.hexEquals(shape.d) } ?: continue
            receiptKey = deliveryReceiptKey(stream.key)
            val envelope = openEnvelope(Json.parseToJsonElement(Nip44.decrypt(event.content, receiptKey))) ?: continue
            val body = Json.parseToJsonElement(envelope.body) as? JsonObject ?: continue
            val named = body.string("device") ?: continue
            if (!named.hexEquals(device)) continue
            for (index in 0 until MAX_DELIVERY_RECEIPT_INDEX) {
                val secretKey = deliveryReceiptOuterSecretKey(deviceSecretKey, shape.d, event.createdAt, index)
                if (Schnorr.publicKeyHex(secretKey).hexEquals(event.pubkey)) {
                    out.add(OwnDeliveryReceipt(event, secretKey)) // The caller now owns its erasure.
                    break
                }
                secretKey.fill(0)
            }
        } catch (_: Exception) {
            // Not ours, or not readable here: nothing to ask for.
        } finally {
            receiptKey?.fill(0)
        }
    }
    return out
}

private class ReceiptShape(val d: String, val expiration: Long)

/** The routing a receipt may carry, checked on the wire fields alone. */
private fun boundedReceipt(event: NostrEvent): ReceiptShape? {
    if (event.kind != KIND_DELIVERY_RECEIPT) return null
    if (event.content.length > MAX_DELIVERY_RECEIPT_EVENT_BYTES) return null
    if (event.createdAt !in 0..MAX_SAFE_INTEGER) return null
    if (!HEX64_ANY_CASE.matches(event.id) || !HEX64_ANY_CASE.matches(event.pubkey) || !HEX128_ANY_CASE.matches(event.sig)) return null
    if (event.tags.size > MAX_DELIVERY_RECEIPT_TAGS) return null
    val seen = HashSet<String>()
    var d: String? = null
    var expiration: Long? = null
    for (tag in event.tags) {
        if (tag.size < 2) return null
        if (tag.any { it.length > MAX_DELIVERY_RECEIPT_EVENT_BYTES }) return null
        val name = tag[0]
        if (name.isEmpty() || !seen.add(name)) return null
        if (name == "d") {
            if (tag.size != 2 || !HEX64_ANY_CASE.matches(tag[1])) return null
            d = tag[1]
        } else if (name == "expiration") {
            if (tag.size != 2 || !DECIMAL.matches(tag[1])) return null
            val value = tag[1].toLongOrNull() ?: return null
            if (value > MAX_SAFE_INTEGER) return null
            expiration = value
        }
    }
    if (d == null || expiration == null) return null
    if (expiration <= event.createdAt) return null
    if (expiration > event.createdAt + DELIVERY_RECEIPT_TTL_SECONDS + DELIVERY_RECEIPT_FUTURE_SKEW_SECONDS) return null
    if (wireBytes(event) > MAX_DELIVERY_RECEIPT_EVENT_BYTES) return null
    return ReceiptShape(d, expiration)
}

private class ReceiptEnvelope(val body: String, val sig: String)

private fun openEnvelope(raw: kotlinx.serialization.json.JsonElement): ReceiptEnvelope? {
    val value = raw as? JsonObject ?: return null
    if (value.keys != setOf("body", "sig", "v")) return null
    if (!value.isNumber("v", 1)) return null
    val body = value.string("body") ?: return null
    val sig = value.string("sig")?.takeIf { HEX128.matches(it) } ?: return null
    return ReceiptEnvelope(body, sig)
}

private fun wellFormedGot(raw: kotlinx.serialization.json.JsonElement?): List<String>? {
    val array = raw as? JsonArray ?: return null
    if (array.isEmpty() || array.size > MAX_DELIVERY_RECEIPT_IDS) return null
    val seen = LinkedHashSet<String>()
    for (entry in array) {
        val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!ID_HASH.matches(text) || !seen.add(text)) return null
    }
    return seen.toList()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Whether [key] is the JSON number [expected] (not the string "1", not 1.5, not null). */
private fun JsonObject.isNumber(key: String, expected: Long): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return false
    return !primitive.isString && primitive.content.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal(expected)) == 0
}

/**
 * The credential as the web client writes it into a receipt body:
 * `kind, created_at, pubkey, tags, content, id, sig`. Not [NostrEvent.toJson],
 * whose order differs; the order is part of the bytes the device signs.
 */
private fun credentialJson(event: NostrEvent): JsonObject = buildJsonObject {
    put("kind", event.kind)
    put("created_at", event.createdAt)
    put("pubkey", event.pubkey)
    put("tags", tagsToJson(event.tags))
    put("content", event.content)
    put("id", event.id)
    put("sig", event.sig)
}

/**
 * The UTF-8 size of the event's wire fields, as JavaScript's `JSON.stringify`
 * would write them: `{id, pubkey, sig, kind, created_at, tags, content}`.
 */
private fun wireBytes(event: NostrEvent): Int {
    val out = StringBuilder(event.content.length + 512)
    out.append("{\"id\":"); jsQuote(out, event.id)
    out.append(",\"pubkey\":"); jsQuote(out, event.pubkey)
    out.append(",\"sig\":"); jsQuote(out, event.sig)
    out.append(",\"kind\":").append(event.kind)
    out.append(",\"created_at\":").append(event.createdAt)
    out.append(",\"tags\":[")
    event.tags.forEachIndexed { i, tag ->
        if (i > 0) out.append(',')
        out.append('[')
        tag.forEachIndexed { j, value ->
            if (j > 0) out.append(',')
            jsQuote(out, value)
        }
        out.append(']')
    }
    out.append("],\"content\":"); jsQuote(out, event.content)
    out.append('}')
    return out.toString().toByteArray(Charsets.UTF_8).size
}

/** `JSON.stringify` of a string: short escapes, `\u00xx` controls and lone surrogates, nothing else. */
private fun jsQuote(out: StringBuilder, s: String) {
    out.append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                out.append(c).append(s[i + 1])
                i++
            }
            Character.isSurrogate(c) -> out.append("\\u").append(String.format("%04x", c.code))
            else -> out.append(c)
        }
        i++
    }
    out.append('"')
}
