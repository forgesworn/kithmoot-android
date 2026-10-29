package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Spry signing key a device uses in one room (the web's `src/spry-key.ts`).
 *
 * Device keys are per room, so this key is per room per device. Members learn
 * nothing new from its public half: it is a one-way derivation of a key whose
 * public half they already see. A roster entry announces the public half as
 * `spryKey`, so a paired member's app can register it with its own Spry node
 * and the room's relay grant then redeems only for admitted devices.
 */
object SpryKey {
    private val HEX64 = Regex("[0-9a-fA-F]{64}")

    /** The secret Spry key for a room device key: HMAC-SHA256(device key, "spry-device-v1"). */
    fun secret(deviceSecretKey: ByteArray): ByteArray =
        Digests.hmacSha256(deviceSecretKey, "spry-device-v1".toByteArray(Charsets.UTF_8))

    /** Its BIP-340 x-only public key, 64 lowercase hex: what a roster entry announces as `spryKey`. */
    fun publicKeyHex(deviceSecretKey: ByteArray): String {
        val secret = secret(deviceSecretKey)
        return try { Schnorr.publicKeyHex(secret) } finally { secret.fill(0) }
    }

    /** A roster `spryKey` claim: 64 hex naming an x-only point on the curve,
     *  lower-cased, or null. The node refuses a whole registration that names
     *  an invalid point, so one is dropped here, at the boundary. */
    fun sanitise(value: JsonElement?): String? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return sanitise(text)
    }

    fun sanitise(value: String?): String? {
        if (value == null || !HEX64.matches(value)) return null
        val key = value.lowercase()
        return key.takeIf { Schnorr.liftsToPoint(it.hexToBytes()) }
    }

    /** The secret key as hex, for signing Spry requests. */
    fun secretHex(deviceSecretKey: ByteArray): String = secret(deviceSecretKey).toHex()
}
