package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex

/** A device credential: one participant authorising one device, in one room. */
const val KIND_DEVICE_CREDENTIAL: Int = 20460

/** The outcome of checking a credential. Reasons are part of the wire contract. */
sealed interface CredentialCheck {
    data class Valid(val participant: String, val device: String) : CredentialCheck
    data class Invalid(val reason: String) : CredentialCheck
}

/**
 * Mints a room-scoped device credential.
 *
 * The participant key signs; the device key is only named. That separation is
 * the whole point - the participant key never has to be on the phone, and a
 * device that is lost is contained by the room it was scoped to and the expiry
 * it was given.
 *
 * Expiry rides in a NIP-40 `expiration` tag, so relays that honour NIP-40 will
 * drop the credential on their own once it lapses.
 */
fun createDeviceCredential(
    participantSecretKey: ByteArray,
    devicePubkey: String,
    roomId: String,
    expiresAt: Long,
    createdAt: Long = System.currentTimeMillis() / 1000,
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent = Events.sign(
    secretKey = participantSecretKey,
    kind = KIND_DEVICE_CREDENTIAL,
    createdAt = createdAt,
    tags = listOf(
        listOf("d", roomId),
        listOf("device", devicePubkey),
        listOf("expiration", expiresAt.toString()),
    ),
    content = "",
    auxRand = auxRand,
)

/**
 * Checks a credential against the room presenting it.
 *
 * The order is deliberate: the cheap structural checks (right kind, right room)
 * come first, then the expiry, and the signature - by far the most expensive
 * step - only once everything else has already agreed. A credential aimed at
 * another room never costs us a curve operation.
 *
 * Check order and reason strings both match the TypeScript reference
 * implementation exactly - see `vectors/README.md`'s "Reason strings are
 * normative" section. `toLongOrNull()` already folds a missing tag and a
 * present-but-non-numeric one into the same `"no expiration"` outcome,
 * matching the reference's explicit `Number.isFinite` guard: neither
 * implementation's expiry check has a fail-open path for a corrupted tag.
 */
fun verifyDeviceCredential(event: NostrEvent, roomId: String, now: Long): CredentialCheck {
    if (event.kind != KIND_DEVICE_CREDENTIAL) return CredentialCheck.Invalid("wrong kind")
    if (event.tagValue("d")?.hexEquals(roomId) != true) return CredentialCheck.Invalid("wrong room")

    val expiresAt = event.tagValue("expiration")?.toLongOrNull()
        ?: return CredentialCheck.Invalid("no expiration")
    if (expiresAt <= now) return CredentialCheck.Invalid("expired")

    val device = event.tagValue("device") ?: return CredentialCheck.Invalid("no device")

    if (!Events.verify(event)) return CredentialCheck.Invalid("bad signature")

    // A credential is one of the places a device/participant pubkey enters
    // the system - the `device` tag in particular is free text set by
    // whoever minted the credential. Canonicalise both here so every caller
    // (roster decode, secondary-device adoption) compares against something
    // already lower-case, rather than each having to know to.
    return CredentialCheck.Valid(participant = event.pubkey.normaliseHex(), device = device.normaliseHex())
}
