package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
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
            val admitted = (json["admitted"] as? JsonArray)?.map { it.jsonPrimitive.content }
            return RoomPolicy(tier, admitted)
        }
    }
}

/** An issuer's signed statement that a participant stands at some tier. */
data class KindredProof(
    val tier: KindredTier,
    val participant: String,
    val issuer: String,
    val sig: String,
    val expiresAt: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tier", tier.wire)
        put("participant", participant)
        put("issuer", issuer)
        put("sig", sig)
        put("expiresAt", expiresAt)
    }

    companion object {
        /** Null when the tier is unrecognised, rather than an exception in a hot path. */
        fun fromJson(json: JsonObject): KindredProof? {
            val tier = KindredTier.fromWire(json["tier"]?.jsonPrimitive?.content ?: return null) ?: return null
            return KindredProof(
                tier = tier,
                participant = json.getValue("participant").jsonPrimitive.content,
                issuer = json.getValue("issuer").jsonPrimitive.content,
                sig = json.getValue("sig").jsonPrimitive.content,
                expiresAt = json.getValue("expiresAt").jsonPrimitive.long,
            )
        }
    }
}

/** The message a kindred proof signs. The issuer is bound by the verifying key. */
fun kindredProofMessage(tier: KindredTier, participant: String, expiresAt: Long): ByteArray =
    Digests.sha256("kithmoot/v1/kindred:${tier.wire}:$participant:$expiresAt".toByteArray(Charsets.UTF_8))

fun issueKindredProof(
    issuerSecretKey: ByteArray,
    participant: String,
    tier: KindredTier,
    expiresAt: Long,
    auxRand: ByteArray = Entropy.bytes(32),
): KindredProof {
    val message = kindredProofMessage(tier, participant, expiresAt)
    return KindredProof(
        tier = tier,
        participant = participant,
        issuer = Schnorr.publicKeyHex(issuerSecretKey),
        sig = Schnorr.sign(message, issuerSecretKey, auxRand).toHex(),
        expiresAt = expiresAt,
    )
}

/** Signature-only check. Never throws. */
fun verifyKindredProof(proof: KindredProof): Boolean = try {
    Schnorr.verify(
        proof.sig.hexToBytes(),
        kindredProofMessage(proof.tier, proof.participant, proof.expiresAt),
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
 */
fun evaluateAccess(
    policy: RoomPolicy,
    participant: String,
    proof: KindredProof?,
    now: Long,
): AccessDecision {
    if (policy.tier == KindredTier.OPEN) return AccessDecision(true, "open room")
    if (proof == null) return AccessDecision(false, "no kindred proof")
    if (proof.participant != participant) return AccessDecision(false, "proof names another participant")

    val admitted = policy.admitted.orEmpty()
    if (proof.issuer !in admitted) return AccessDecision(false, "untrusted issuer")
    if (proof.expiresAt <= now) return AccessDecision(false, "proof expired")
    if (proof.tier.closeness < policy.tier.closeness) return AccessDecision(false, "tier too low")
    if (!verifyKindredProof(proof)) return AccessDecision(false, "bad proof signature")

    return AccessDecision(true, "kindred proof accepted")
}
