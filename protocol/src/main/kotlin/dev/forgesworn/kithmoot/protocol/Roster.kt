package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A roster entry: who is here, on what device, publishing what. */
const val KIND_ROSTER: Int = 20461

/**
 * How far ahead of our own clock a roster timestamp may be stamped.
 *
 * [RosterEntry.updatedAt] decides which of two entries for one device wins, and
 * a singular-role claim time decides which of a participant's devices holds the
 * microphone. Both are chosen by the device that publishes them, so a device
 * stamping the year 3000 pins itself into the roster for good and locks the mic
 * against its owner's other devices - neither can ever be superseded by a
 * genuine later value. The bound has to be loose enough that real clocks, which
 * disagree by seconds, are not refused.
 */
const val MAX_FUTURE_SKEW_SECONDS: Long = 60

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
    /**
     * What this person would like to be called.
     *
     * Self-asserted, always: anybody can type anything, and nothing here or
     * anywhere else checks it. It is a label on a pubkey, never a substitute
     * for one - `DisplayName.sanitise` bounds what it can look like, and
     * every renderer is required to show a short pubkey beside it so two
     * people called "Robin" stay apart and an impersonation is visible.
     *
     * Null when nobody typed one, which keeps the wire byte-identical for
     * anyone who does not use this at all.
     */
    val name: String? = null,
    val tracks: List<TrackRef> = emptyList(),
    val claims: Map<String, Long> = emptyMap(),
    val updatedAt: Long,
    val proof: KindredProof? = null,
    /**
     * True when this entry is not an arrival: an answer to somebody else's
     * arrival, or a farewell. Neither provokes an answer, which is what stops
     * the room talking to itself for ever. Absent on a first announcement.
     */
    /**
     * True when this device is an automated participant: an agent that acts
     * for a person, or for itself, and is in the room to read, write and
     * listen rather than to be looked at.
     *
     * Self-declared, and a claim like every other field here - nothing stops
     * a person's client saying it and nothing stops an agent not saying it.
     * What it is FOR is consent: a member may choose not to send its media to
     * anything that says it is an agent, and a room may show which of its
     * members are people. An agent that hides the flag receives media it was
     * not meant to have, which is the same betrayal as a person recording a
     * call, and no protocol prevents either. Absent on every entry that is
     * not one, so the wire is byte-identical for a client that has never
     * heard of agents.
     */
    val agent: Boolean = false,
    val reply: Boolean = false,
    /**
     * True on the last entry a device publishes: it has left the room.
     *
     * Departure is a stated fact rather than a guess from an empty track list,
     * because a device with everything switched off looks exactly like one on
     * its way out and only one of them should vanish. A receiver drops the
     * device at once instead of waiting out the presence timeout. Only a JSON
     * `true` is a farewell; anything else is an ordinary entry. Absent on
     * every entry that is not one, so the wire stays byte-identical.
     */
    val left: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("participant", participant)
        put("device", device)
        put("credential", credential.toJson())
        // Sanitised on the way out as well as on the way in. Out, so this
        // client never publishes something another has to defuse; in (see
        // `fromJson`), because no other client is obliged to have bothered.
        DisplayName.sanitise(name)?.let { put("name", it) }
        proof?.let { put("proof", it.toJson()) }
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
        if (reply) put("reply", true)
        if (agent) put("agent", true)
        if (left) put("left", true)
    }

    companion object {
        fun fromJson(json: JsonObject): RosterEntry = RosterEntry(
            participant = json.getValue("participant").jsonPrimitive.content,
            device = json.getValue("device").jsonPrimitive.content,
            credential = NostrEvent.fromJson(json.getValue("credential").jsonObject),
            proof = (json["proof"] as? JsonObject)?.let { KindredProof.fromJson(it) },
            tracks = (json["tracks"] as? JsonArray).orEmptyArray().map {
                val track = it.jsonObject
                TrackRef(
                    trackId = track.getValue("trackId").jsonPrimitive.content,
                    role = track.getValue("role").jsonPrimitive.content,
                )
            },
            claims = (json["claims"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.long }.orEmpty(),
            name = DisplayName.sanitise((json["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content),
            updatedAt = json.getValue("updatedAt").jsonPrimitive.long,
            reply = json["reply"].isHonestTrue(),
            // Only an honest `true` declares an agent, for the same reason
            // only an honest `true` is a farewell: the flag decides what a
            // member sends this device, so a looser client's `1` or `"yes"`
            // is a person.
            agent = json["agent"].isHonestTrue(),
            left = json["left"].isHonestTrue(),
        )

        /** A JSON `true` and nothing else: not `"true"`, not `1`, not `"yes"`. */
        private fun JsonElement?.isHonestTrue(): Boolean =
            this is JsonPrimitive && !isString && content == "true"
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
            // A timestamp beyond clock skew is a pin, not a clock - see
            // [MAX_FUTURE_SKEW_SECONDS]. The entry goes; a claim only costs
            // the claim, because a device with one bad claim is still in the
            // room.
            val horizon = now + MAX_FUTURE_SKEW_SECONDS
            when {
                !entry.device.hexEquals(event.pubkey) -> null
                credential !is CredentialCheck.Valid -> null
                !credential.device.hexEquals(entry.device) -> null
                !credential.participant.hexEquals(entry.participant) -> null
                entry.updatedAt > horizon -> null
                else -> entry.copy(claims = entry.claims.filterValues { it <= horizon })
            }
        }
    }
} catch (_: Exception) {
    null
}
