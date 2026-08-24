package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The kindred triad, plus `open`.
 *
 * [closeness] is the only thing that decides whether a proof clears a gate, and
 * the ordering is deliberate: **ken is one-way recognition** - you pinned
 * someone's key, they never vouched for you - so a ken proof must never satisfy
 * a kith gate, which asks for a mutual, verified bond.
 */
enum class KindredTier(val wire: String, val closeness: Int) {
    OPEN("open", 0),
    KEN("ken", 1),
    KITH("kith", 2),
    KIN("kin", 3),
    ;

    companion object {
        /** Null for anything we do not recognise, so callers fail closed. */
        fun fromWire(wire: String): KindredTier? = entries.firstOrNull { it.wire == wire }
    }
}

/** A room's access gate: the tier required, and whose vouching counts. */
data class RoomPolicy(val tier: KindredTier, val admitted: List<String>? = null) {

    fun toJson(): JsonObject = buildJsonObject {
        put("tier", tier.wire)
        if (admitted != null) {
            put("admitted", buildJsonArray { for (issuer in admitted) add(JsonPrimitive(issuer)) })
        }
    }

    companion object {
        /** Null when the tier is not one we recognise - never a silent downgrade. */
        fun fromJson(json: JsonObject): RoomPolicy? {
            val tier = KindredTier.fromWire(json["tier"]?.jsonPrimitive?.content ?: return null) ?: return null
            // The allow-list is exactly the case this rule was written for:
            // entries a person typed or pasted into a link. Canonicalise
            // here, at the point they enter the system off the URL, rather
            // than relying on every reader to compare them case-insensitively.
            val admitted = (json["admitted"] as? JsonArray)?.map { it.jsonPrimitive.content.normaliseHex() }
            return RoomPolicy(tier, admitted)
        }
    }
}

/**
 * An issuer's signed statement that a participant stands at some tier, in one
 * room, until an expiry.
 *
 * [room] is what stops a proof being a bearer token: without it, one proof
 * admits its holder to every room that happens to trust the same issuer, and an
 * issuer who vouched for a guest at one moot has not vouched for them at all of
 * them. The cost of that binding is stated plainly: a kindred proof is a **room
 * grant**, not a portable statement about a relationship, so an issuer mints one
 * per room. In this protocol the party who vouches is the party who sent the
 * join link, so it already knows the room id.
 */
data class KindredProof(
    val tier: KindredTier,
    val participant: String,
    val issuer: String,
    /** The room id this proof is valid in. */
    val room: String,
    /** 32 random bytes, hex, unique to this proof. Signed over, so two proofs
     *  on identical terms are still distinguishable - which is what a
     *  revocation list, or an audit, needs to name one of them. */
    val nonce: String,
    val sig: String,
    val expiresAt: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tier", tier.wire)
        put("participant", participant)
        put("issuer", issuer)
        put("room", room)
        put("nonce", nonce)
        put("sig", sig)
        put("expiresAt", expiresAt)
    }

    companion object {
        /** Null when the tier is unrecognised, or when the room binding is
         *  missing entirely - both fail closed rather than throwing in a hot
         *  path. */
        fun fromJson(json: JsonObject): KindredProof? {
            val tier = KindredTier.fromWire(json["tier"]?.jsonPrimitive?.content ?: return null) ?: return null
            return KindredProof(
                tier = tier,
                participant = json.getValue("participant").jsonPrimitive.content,
                issuer = json.getValue("issuer").jsonPrimitive.content,
                room = json["room"]?.jsonPrimitive?.content ?: return null,
                nonce = json["nonce"]?.jsonPrimitive?.content ?: return null,
                sig = json.getValue("sig").jsonPrimitive.content,
                expiresAt = json.getValue("expiresAt").jsonPrimitive.long,
            )
        }
    }
}

/**
 * The message a kindred proof signs. The issuer is bound by the verifying key.
 *
 * Room and nonce are covered deliberately - see [KindredProof]. A proof signed
 * by an implementation that omits them reconstructs a different message and
 * fails the signature check, which is the right way round: an older proof is
 * refused rather than silently admitted somewhere it was never meant to go.
 */
fun kindredProofMessage(
    tier: KindredTier,
    participant: String,
    room: String,
    nonce: String,
    expiresAt: Long,
): ByteArray = Digests.sha256(
    "kithmoot/v1/kindred:${tier.wire}:$participant:$room:$nonce:$expiresAt".toByteArray(Charsets.UTF_8),
)

fun issueKindredProof(
    issuerSecretKey: ByteArray,
    participant: String,
    tier: KindredTier,
    roomId: String,
    expiresAt: Long,
    /** 32 bytes, hex. Supply one only to make a proof reproducible - the
     *  interop vectors do; everything else wants the random default. */
    nonce: String = Entropy.bytes(32).toHex(),
    auxRand: ByteArray = Entropy.bytes(32),
): KindredProof {
    // `participant` and `roomId` are identifiers handed in by the caller -
    // possibly typed or pasted - so they are canonicalised here, at the point
    // they enter the proof, rather than left for `evaluateAccess`'s equality
    // checks to paper over.
    val normalisedParticipant = participant.normaliseHex()
    val normalisedRoom = roomId.normaliseHex()
    val normalisedNonce = nonce.normaliseHex()
    val message = kindredProofMessage(tier, normalisedParticipant, normalisedRoom, normalisedNonce, expiresAt)
    return KindredProof(
        tier = tier,
        participant = normalisedParticipant,
        issuer = Schnorr.publicKeyHex(issuerSecretKey),
        room = normalisedRoom,
        nonce = normalisedNonce,
        sig = Schnorr.sign(message, issuerSecretKey, auxRand).toHex(),
        expiresAt = expiresAt,
    )
}

/** Signature-only check. Never throws. */
fun verifyKindredProof(proof: KindredProof): Boolean = try {
    Schnorr.verify(
        proof.sig.hexToBytes(),
        kindredProofMessage(proof.tier, proof.participant, proof.room, proof.nonce, proof.expiresAt),
        proof.issuer.hexToBytes(),
    )
} catch (_: Exception) {
    false
}

data class AccessDecision(val admitted: Boolean, val reason: String)

/**
 * The gate. Everything about it fails closed: no proof, an unknown issuer, a
 * lapsed proof or a tier below the bar all end in refusal, and the signature is
 * checked last so a forged proof cannot short-circuit the cheaper checks.
 *
 * Check order and reason strings both match the TypeScript reference
 * implementation exactly - see `vectors/README.md`'s "Reason strings are
 * normative" section. Both matter: two implementations that reject the same
 * input for different reasons, or in a different order, can disagree on
 * which reason a caller sees for an input that fails more than one check.
 *
 * `participant` and `issuer` are hex pubkeys, compared via [hexEquals]
 * throughout: see `vectors/README.md`'s "Hex identifiers are compared
 * case-insensitively" section.
 */
fun evaluateAccess(
    policy: RoomPolicy,
    participant: String,
    proof: KindredProof?,
    now: Long,
    roomId: String,
): AccessDecision {
    if (policy.tier == KindredTier.OPEN) return AccessDecision(true, "open room")
    if (proof == null) return AccessDecision(false, "no kindred proof")
    if (!proof.participant.hexEquals(participant)) return AccessDecision(false, "proof names another participant")
    // A proof is a grant in one room, not a bearer token - see [KindredProof].
    if (!proof.room.hexEquals(roomId)) return AccessDecision(false, "proof names another room")
    if (proof.expiresAt <= now) return AccessDecision(false, "expired")

    val admitted = policy.admitted.orEmpty()
    if (admitted.none { it.hexEquals(proof.issuer) }) return AccessDecision(false, "untrusted issuer")
    if (proof.tier.closeness < policy.tier.closeness) return AccessDecision(false, "tier too low")
    if (!verifyKindredProof(proof)) return AccessDecision(false, "bad signature")

    return AccessDecision(true, "kindred proof accepted")
}
