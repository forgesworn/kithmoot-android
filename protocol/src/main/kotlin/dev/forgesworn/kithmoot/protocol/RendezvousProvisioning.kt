package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** Vennel §3.2's only allowed root-child purpose. */
const val RENDEZVOUS_PURPOSE = "rendezvous"
const val RENDEZVOUS_PROVISION_MAX_SECONDS = 10 * 60L

/** A decrypted provision record, to be moved into the device vault immediately. */
data class RendezvousProvision(
    val index: Long,
    val expiresAt: Long,
    val scalar: ByteArray,
) {
    fun wipe() = scalar.fill(0)
}

sealed class RendezvousProvisionResult {
    data class Accepted(val provision: RendezvousProvision) : RendezvousProvisionResult()
    data class Refused(val reason: String) : RendezvousProvisionResult()
}

/**
 * The public wrapper returned by Heartwood's narrowly-scoped NIP-46
 * operation.  `ciphertext` is encrypted to `device` using `rendezvous`, so a
 * caller must check these bindings before deriving the NIP-44 conversation
 * key to open it.
 */
data class RendezvousProvisionEnvelope(
    val rendezvousPubkey: String,
    val ciphertext: String,
)

sealed class RendezvousProvisionEnvelopeResult {
    data class Accepted(val envelope: RendezvousProvisionEnvelope) : RendezvousProvisionEnvelopeResult()
    data class Refused(val reason: String) : RendezvousProvisionEnvelopeResult()
}

data class RendezvousProvisionExpect(
    val identity: String,
    val device: String,
    val nonce: ByteArray,
    val now: Long,
)

private val provisionFields = listOf("v", "p", "d", "rz", "u", "i", "n", "e", "k")
private val envelopeFields = listOf("v", "p", "d", "rz", "u", "i", "n", "e", "c")
private val lowerHex64 = Regex("^[0-9a-f]{64}$")
private val base64Url = Regex("^[A-Za-z0-9_-]+$")

/**
 * Check an already NIP-44-decrypted Vennel provision record. This module does
 * not decrypt, transport or persist the scalar: those steps are deliberately
 * bound to the approved pairing ceremony rather than to contact storage.
 */
fun readRendezvousProvision(text: String, expect: RendezvousProvisionExpect): RendezvousProvisionResult {
    if (text.length > 1024) return RendezvousProvisionResult.Refused("size")
    val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return RendezvousProvisionResult.Refused("json")
    if (body.keys.toList() != provisionFields) return RendezvousProvisionResult.Refused("fields")
    if (body.toString() != text) return RendezvousProvisionResult.Refused("canonical")
    if (body.long("v") != 1L) return RendezvousProvisionResult.Refused("version")
    val identity = body.text("p") ?: return RendezvousProvisionResult.Refused("pubkey")
    val device = body.text("d") ?: return RendezvousProvisionResult.Refused("pubkey")
    val rendezvous = body.text("rz") ?: return RendezvousProvisionResult.Refused("pubkey")
    if (!lowerHex64.matches(identity) || !lowerHex64.matches(device) || !lowerHex64.matches(rendezvous)) return RendezvousProvisionResult.Refused("pubkey")
    if (body.text("u") != RENDEZVOUS_PURPOSE) return RendezvousProvisionResult.Refused("purpose")
    val index = body.long("i") ?: return RendezvousProvisionResult.Refused("index")
    if (index !in 0..0xffffffffL) return RendezvousProvisionResult.Refused("index")
    val expiresAt = body.long("e") ?: return RendezvousProvisionResult.Refused("expired")
    if (expiresAt <= expect.now) return RendezvousProvisionResult.Refused("expired")
    if (expiresAt - expect.now > RENDEZVOUS_PROVISION_MAX_SECONDS) return RendezvousProvisionResult.Refused("expiry window")
    val nonce = body.text("n")?.let(::decodeBase64Url) ?: return RendezvousProvisionResult.Refused("nonce")
    if (nonce.size != 16 || !nonce.contentEquals(expect.nonce)) return RendezvousProvisionResult.Refused("nonce")
    val scalar = body.text("k")?.let(::decodeBase64Url) ?: return RendezvousProvisionResult.Refused("scalar")
    if (scalar.size != 32) return RendezvousProvisionResult.Refused("scalar")
    if (identity != expect.identity) return RendezvousProvisionResult.Refused("identity")
    if (device != expect.device) return RendezvousProvisionResult.Refused("device")
    if (runCatching { Schnorr.publicKeyHex(scalar) }.getOrNull() != rendezvous) {
        scalar.fill(0)
        return RendezvousProvisionResult.Refused("rendezvous key")
    }
    return RendezvousProvisionResult.Accepted(RendezvousProvision(index, expiresAt, scalar))
}

/**
 * Verify the public NIP-46 response wrapper before trying to decrypt its
 * ciphertext.  The inner record is still checked by [readRendezvousProvision]
 * before anything reaches a device vault.
 */
fun readRendezvousProvisionEnvelope(
    text: String,
    expect: RendezvousProvisionExpect,
): RendezvousProvisionEnvelopeResult {
    if (text.length > 8192) return RendezvousProvisionEnvelopeResult.Refused("size")
    val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return RendezvousProvisionEnvelopeResult.Refused("json")
    // Unlike the inner record, the NIP-46 result is a public JSON wrapper
    // built by the signer.  Its members need not retain a transport-specific
    // order, but it must contain precisely this bounded shape.
    if (body.keys != envelopeFields.toSet()) return RendezvousProvisionEnvelopeResult.Refused("fields")
    if (body.long("v") != 1L) return RendezvousProvisionEnvelopeResult.Refused("version")
    val identity = body.text("p") ?: return RendezvousProvisionEnvelopeResult.Refused("pubkey")
    val device = body.text("d") ?: return RendezvousProvisionEnvelopeResult.Refused("pubkey")
    val rendezvous = body.text("rz") ?: return RendezvousProvisionEnvelopeResult.Refused("pubkey")
    if (!lowerHex64.matches(identity) || !lowerHex64.matches(device) || !lowerHex64.matches(rendezvous)) {
        return RendezvousProvisionEnvelopeResult.Refused("pubkey")
    }
    if (body.text("u") != RENDEZVOUS_PURPOSE) return RendezvousProvisionEnvelopeResult.Refused("purpose")
    val index = body.long("i") ?: return RendezvousProvisionEnvelopeResult.Refused("index")
    if (index !in 0..0xffffffffL) return RendezvousProvisionEnvelopeResult.Refused("index")
    val expiresAt = body.long("e") ?: return RendezvousProvisionEnvelopeResult.Refused("expired")
    if (expiresAt <= expect.now) return RendezvousProvisionEnvelopeResult.Refused("expired")
    if (expiresAt - expect.now > RENDEZVOUS_PROVISION_MAX_SECONDS) return RendezvousProvisionEnvelopeResult.Refused("expiry window")
    val nonce = body.text("n")?.let(::decodeBase64Url) ?: return RendezvousProvisionEnvelopeResult.Refused("nonce")
    if (nonce.size != 16 || !nonce.contentEquals(expect.nonce)) return RendezvousProvisionEnvelopeResult.Refused("nonce")
    if (identity != expect.identity) return RendezvousProvisionEnvelopeResult.Refused("identity")
    if (device != expect.device) return RendezvousProvisionEnvelopeResult.Refused("device")
    val ciphertext = body.text("c")?.takeIf { it.isNotBlank() } ?: return RendezvousProvisionEnvelopeResult.Refused("ciphertext")
    return RendezvousProvisionEnvelopeResult.Accepted(RendezvousProvisionEnvelope(rendezvous, ciphertext))
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun decodeBase64Url(value: String): ByteArray? {
    if (!base64Url.matches(value)) return null
    return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
        ?.takeIf { Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value }
}
