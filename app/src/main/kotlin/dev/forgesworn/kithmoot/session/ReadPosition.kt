package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Where a person has read to, per room and per channel, on the wire once per
 * participant: a kind-30078 record signed by the participant key, replaceable
 * per room by a `d` tag derived from the room key, encrypted to the
 * participant's own key. See docs/messages.md in the reference
 * implementation and the `readPosition` vectors.
 */
const val READ_POSITION_KIND: Int = 30078
const val READ_POSITION_LABEL: String = "kithmoot.read.v1"
private const val READ_POSITION_ID_INFO = "kithmoot/v1/read-position-id"

fun readPositionId(roomKey: ByteArray): String =
    Digests.hkdfSha256(roomKey, null, READ_POSITION_ID_INFO.toByteArray(Charsets.UTF_8), 32).toHex()

data class ReadPosition(val at: Long, val id: String? = null)

data class ReadPositionRecord(val room: String, val read: Map<String, ReadPosition>)

/** The canonical body both implementations write: channels sorted, `id` only when there is one. */
fun readPositionPlaintext(room: String, read: Map<String, ReadPosition>): String = buildJsonObject {
    put("v", 1)
    put("room", room.normaliseHex())
    put("read", buildJsonObject {
        for (channel in read.keys.sorted()) {
            val p = read.getValue(channel)
            put(channel, buildJsonObject { put("at", p.at); if (p.id != null) put("id", p.id) })
        }
    })
}.toString()

private fun selfKey(participantSecretKey: ByteArray): ByteArray =
    Nip44.conversationKey(participantSecretKey, Schnorr.publicKeyHex(participantSecretKey).hexToBytes())

fun encodeReadPositions(
    read: Map<String, ReadPosition>,
    roomId: String,
    roomKey: ByteArray,
    participantSecretKey: ByteArray,
    createdAt: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent = Events.sign(
    secretKey = participantSecretKey,
    kind = READ_POSITION_KIND,
    createdAt = createdAt,
    tags = listOf(listOf("d", readPositionId(roomKey)), listOf("l", READ_POSITION_LABEL)),
    content = Nip44.encrypt(readPositionPlaintext(roomId, read), selfKey(participantSecretKey), nonce),
    auxRand = auxRand,
)

/** Reads a record, or null. Never throws. */
fun decodeReadPositions(
    event: NostrEvent,
    participant: String,
    roomId: String,
    roomKey: ByteArray,
    participantSecretKey: ByteArray,
): ReadPositionRecord? {
    return try {
    when {
        event.kind != READ_POSITION_KIND -> null
        !event.pubkey.hexEquals(participant) -> null
        event.tagValue("d")?.hexEquals(readPositionId(roomKey)) != true -> null
        event.tags.none { it.size >= 2 && it[0] == "l" && it[1] == READ_POSITION_LABEL } -> null
        !Events.verify(event) -> null
        else -> {
            val body = Json.parseToJsonElement(Nip44.decrypt(event.content, selfKey(participantSecretKey))).jsonObject
            if (body["v"]?.jsonPrimitive?.int != 1) return null
            val room = (body["room"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!room.hexEquals(roomId)) return null
            val rawRead = body["read"] as? JsonObject ?: return null
            val read = LinkedHashMap<String, ReadPosition>()
            for ((channel, raw) in rawRead) {
                if (channel.length > 64) continue
                val p = raw as? JsonObject ?: continue
                val at = (p["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull() ?: continue
                if (at < 0) continue
                val id = (p["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { validMessageId(it) }
                read[channel] = ReadPosition(at, id)
            }
            ReadPositionRecord(room.normaliseHex(), read)
        }
    }
    } catch (_: Exception) {
        null
    }
}

private fun JsonPrimitive.longOrNull(): Long? = runCatching { long }.getOrNull()

/** Whether [a] is further on than [b]. */
fun furtherOn(a: ReadPosition, b: ReadPosition?): Boolean {
    if (b == null) return true
    if (a.at != b.at) return a.at > b.at
    return (a.id ?: "") > (b.id ?: "")
}

data class MergedReadPositions(val merged: Map<String, ReadPosition>, val localAhead: Boolean, val remoteAhead: Boolean)

/** The greater position per channel wins; the flags say who knew more. */
fun mergeReadPositions(local: Map<String, ReadPosition>, remote: Map<String, ReadPosition>): MergedReadPositions {
    val merged = LinkedHashMap<String, ReadPosition>()
    var localAhead = false
    var remoteAhead = false
    for (channel in (local.keys + remote.keys).distinct()) {
        val l = local[channel]
        val r = remote[channel]
        if (l != null && (r == null || furtherOn(l, r))) {
            merged[channel] = l
            localAhead = true
        } else if (r != null) {
            merged[channel] = r
            if (l == null || furtherOn(r, l)) remoteAhead = true
        }
    }
    return MergedReadPositions(merged, localAhead, remoteAhead)
}
