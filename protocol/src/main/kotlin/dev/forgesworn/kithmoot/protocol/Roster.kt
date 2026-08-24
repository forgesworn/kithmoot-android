package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A roster entry: who is here, on what device, publishing what. */
const val KIND_ROSTER: Int = 20461

/** One published media track, attributed to a participant rather than a device. */
data class TrackRef(val trackId: String, val role: String)

/**
 * One device's presence in a room, carrying the credential that proves it may
 * speak for its participant, so any member can check it without asking a server.
 */
data class RosterEntry(
    val participant: String,
    val device: String,
    val credential: NostrEvent,
    val tracks: List<TrackRef> = emptyList(),
    val claims: Map<String, Long> = emptyMap(),
    val updatedAt: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("participant", participant)
        put("device", device)
        put("credential", credential.toJson())
        put(
            "tracks",
            buildJsonArray {
                for (track in tracks) {
                    add(
                        buildJsonObject {
                            put("trackId", track.trackId)
                            put("role", track.role)
                        },
                    )
                }
            },
        )
        put("claims", buildJsonObject { for ((role, since) in claims) put(role, since) })
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JsonObject): RosterEntry = RosterEntry(
            participant = json.getValue("participant").jsonPrimitive.content,
            device = json.getValue("device").jsonPrimitive.content,
            credential = NostrEvent.fromJson(json.getValue("credential").jsonObject),
            tracks = (json["tracks"] as? JsonArray).orEmptyArray().map {
                val track = it.jsonObject
                TrackRef(
                    trackId = track.getValue("trackId").jsonPrimitive.content,
                    role = track.getValue("role").jsonPrimitive.content,
                )
            },
            claims = (json["claims"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.long }.orEmpty(),
            updatedAt = json.getValue("updatedAt").jsonPrimitive.long,
        )
    }
}

private fun JsonArray?.orEmptyArray(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/**
 * Publishes a roster entry, encrypted to the **room key** and signed by the
 * device key.
 *
 * Encrypting to the room rather than gift-wrapping per member is what keeps a
 * 21-person room flat: one event, published once, that every member can read.
 * The relay sees an opaque blob tagged with an opaque room id.
 */
fun encodeRosterEvent(
    entry: RosterEntry,
    roomId: String,
    roomKey: ByteArray,
    deviceSecretKey: ByteArray,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent = Events.sign(
    secretKey = deviceSecretKey,
    kind = KIND_ROSTER,
    createdAt = entry.updatedAt,
    tags = listOf(listOf("d", roomId)),
    content = Nip44.encrypt(entry.toJson().toString(), roomKey, nonce),
    auxRand = auxRand,
)

/**
 * Reads a roster entry, verifying everything about it, and **returns null on
 * any failure rather than throwing**.
 *
 * That is not defensive habit: this runs inside a relay subscription callback,
 * where one malformed event from one hostile publisher would otherwise unwind
 * the subscription and take the whole room down with it. A bad event is simply
 * not a roster entry.
 */
fun decodeRosterEvent(
    event: NostrEvent,
    roomId: String,
    roomKey: ByteArray,
    now: Long,
): RosterEntry? = try {
    when {
        event.kind != KIND_ROSTER -> null
        event.tagValue("d")?.hexEquals(roomId) != true -> null
        !Events.verify(event) -> null
        else -> {
            val decoded = RosterEntry.fromJson(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(Nip44.decrypt(event.content, roomKey))
                    .jsonObject,
            )
            // This is the boundary: a roster entry's device/participant
            // fields are attacker- or other-implementation-controlled JSON,
            // with nothing on the wire forcing lower case. Canonicalise
            // them here, once, so every later comparison downstream -
            // `PeerLink`'s politeness tiebreak, `RoleArbiter`'s device
            // tiebreak, every map/set keyed on a device or participant
            // string - is correct by construction. See `normaliseHex`.
            val entry = decoded.copy(
                device = decoded.device.normaliseHex(),
                participant = decoded.participant.normaliseHex(),
            )
            // The device that signed must be the device the entry names, or a
            // room member could republish someone else's presence. Hex
            // identifiers compared case-insensitively throughout - see
            // `vectors/README.md`.
            val credential = verifyDeviceCredential(entry.credential, roomId, now)
            when {
                !entry.device.hexEquals(event.pubkey) -> null
                credential !is CredentialCheck.Valid -> null
                !credential.device.hexEquals(entry.device) -> null
                !credential.participant.hexEquals(entry.participant) -> null
                else -> entry
            }
        }
    }
} catch (_: Exception) {
    null
}
