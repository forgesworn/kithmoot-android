package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** The two HKDF info strings that separate the public id from the secret key. */
const val ROOM_ID_INFO: String = "kithmoot/v1/room-id"
const val ROOM_KEY_INFO: String = "kithmoot/v1/room-key"

/** Raised when a join URL cannot be trusted. Never swallowed into an open room. */
class JoinUrlException(message: String) : IllegalArgumentException(message)

/**
 * A room, derived from the 32-byte secret the join URL carries.
 *
 * The id is what relays see and tag events with; the key is what the roster and
 * chat are encrypted to. Deriving both from one secret by separate info strings
 * means holding the id tells you nothing about the key.
 */
class Room(val roomId: String, val roomKey: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Room && roomId == other.roomId && roomKey.contentEquals(other.roomKey)

    override fun hashCode(): Int = 31 * roomId.hashCode() + roomKey.contentHashCode()

    override fun toString(): String = "Room(roomId=$roomId, roomKey=<32 bytes>)"
}

fun deriveRoom(secret: ByteArray): Room {
    require(secret.size == 32) { "a room secret is 32 bytes" }
    val roomId = Digests.hkdfSha256(secret, null, ROOM_ID_INFO.toByteArray(Charsets.UTF_8), 32).toHex()
    val roomKey = Digests.hkdfSha256(secret, null, ROOM_KEY_INFO.toByteArray(Charsets.UTF_8), 32)
    return Room(roomId, roomKey)
}

/** What a join URL carries: the room secret, relay hints, and an optional gate. */
class JoinPayload(val secret: ByteArray, val relays: List<String>, val policy: RoomPolicy?) {
    override fun equals(other: Any?): Boolean =
        other is JoinPayload &&
            secret.contentEquals(other.secret) &&
            relays == other.relays &&
            policy == other.policy

    override fun hashCode(): Int =
        (31 * secret.contentHashCode() + relays.hashCode()) * 31 + (policy?.hashCode() ?: 0)

    override fun toString(): String = "JoinPayload(secret=<32 bytes>, relays=$relays, policy=$policy)"
}

/**
 * Encodes a join URL. The payload goes in the **fragment**, never the path or
 * the query: a fragment is not sent to the server, so the room secret does not
 * end up in an access log, a Referer header, or a proxy's history.
 */
fun encodeJoinUrl(
    base: String,
    secret: ByteArray,
    relays: List<String>,
    policy: RoomPolicy? = null,
): String {
    require(secret.size == 32) { "a room secret is 32 bytes" }
    val payload = buildJsonObject {
        put("s", base64UrlEncode(secret))
        put("r", buildJsonArray { for (relay in relays) add(JsonPrimitive(relay)) })
        if (policy != null) put("a", policy.toJson())
    }
    val fragment = base64UrlEncode(payload.toString().toByteArray(Charsets.UTF_8))
    return "$base#$fragment"
}

/**
 * Decodes a join URL, refusing anything it cannot fully understand. A URL that
 * is malformed, carries a short secret, or names a tier we do not recognise is
 * an error - silently falling back to an open room would turn a typo into an
 * unguarded room.
 */
fun decodeJoinUrl(url: String): JoinPayload {
    val fragment = url.substringAfter('#', "")
    if (fragment.isEmpty()) throw JoinUrlException("join URL fragment is not valid")

    val payload: JsonObject = try {
        val json = String(base64UrlDecode(fragment), Charsets.UTF_8)
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        throw JoinUrlException("join URL fragment is not valid")
    }

    val secret = try {
        base64UrlDecode(payload.getValue("s").jsonPrimitive.content)
    } catch (_: Exception) {
        throw JoinUrlException("join URL carries a malformed secret")
    }
    if (secret.size != 32) throw JoinUrlException("join URL carries a malformed secret")

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
    return JoinPayload(secret, relays, policy)
}

internal fun base64UrlEncode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun base64UrlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
