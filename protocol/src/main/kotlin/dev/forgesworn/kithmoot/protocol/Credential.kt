package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex

/** A device credential: one participant authorising one device, in one room or for the person. */
const val KIND_DEVICE_CREDENTIAL: Int = 20460

/** The longest a person-scoped credential may run. A phone that leaves the house is better at seven days. */
const val PERSON_CREDENTIAL_MAX_SECONDS: Long = 30L * 24 * 60 * 60

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
 * Mints a person-scoped device credential: the same event with `d` set to the
 * participant's own pubkey and a `scope` tag, so one credential lets the
 * device act for the person in every room, DM and box that accepts the form.
 * It may not run more than thirty days. Mirrors `createDeviceCredential({
 * scope: 'person' })` in the reference implementation.
 */
fun createPersonCredential(
    participantSecretKey: ByteArray,
    devicePubkey: String,
    expiresAt: Long,
    label: String? = null,
    createdAt: Long = System.currentTimeMillis() / 1000,
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    require(expiresAt - createdAt <= PERSON_CREDENTIAL_MAX_SECONDS) { "a person credential may not run more than 30 days" }
    val participant = dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(participantSecretKey)
    return Events.sign(
        secretKey = participantSecretKey,
        kind = KIND_DEVICE_CREDENTIAL,
        createdAt = createdAt,
        tags = buildList {
            add(listOf("d", participant))
            add(listOf("device", devicePubkey))
            add(listOf("expiration", expiresAt.toString()))
            add(listOf("scope", "person"))
            if (label != null) add(listOf("label", label))
        },
        content = "",
        auxRand = auxRand,
    )
}

/**
 * Checks a person credential for the identity the verifier expects. Only the
 * person form passes here: a room credential is never a person credential.
 */
fun verifyPersonCredential(event: NostrEvent, identity: String, now: Long): CredentialCheck {
    if (event.kind != KIND_DEVICE_CREDENTIAL) return CredentialCheck.Invalid("wrong kind")
    if (event.tagValue("scope") != "person") return CredentialCheck.Invalid("not a person credential")
    val d = event.tagValue("d") ?: return CredentialCheck.Invalid("no scope")
    if (!d.hexEquals(identity) || !d.hexEquals(event.pubkey)) return CredentialCheck.Invalid("wrong person")
    return checkRest(event, now, person = true)
}

/**
 * Checks a credential against the room presenting it. A person credential is
 * accepted in place of a room credential only when the room says so with
 * [acceptPerson], which it does when admitting the person admits their
 * devices. A room credential carrying a `scope` tag is refused.
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
fun verifyDeviceCredential(event: NostrEvent, roomId: String, now: Long, acceptPerson: Boolean = false): CredentialCheck {
    if (event.kind != KIND_DEVICE_CREDENTIAL) return CredentialCheck.Invalid("wrong kind")
    val scope = event.tagValue("scope")
    if (scope != null && scope != "person") return CredentialCheck.Invalid("unknown scope")
    val person = scope == "person"
    if (person) {
        if (!acceptPerson) return CredentialCheck.Invalid("person credential where a room credential was expected")
        val d = event.tagValue("d") ?: return CredentialCheck.Invalid("no scope")
        if (!d.hexEquals(event.pubkey)) return CredentialCheck.Invalid("wrong person")
    } else if (event.tagValue("d")?.hexEquals(roomId) != true) {
        return CredentialCheck.Invalid("wrong room")
    }
    return checkRest(event, now, person)
}

private fun checkRest(event: NostrEvent, now: Long, person: Boolean): CredentialCheck {
    val expiresAt = event.tagValue("expiration")?.toLongOrNull()
        ?: return CredentialCheck.Invalid("no expiration")
    if (expiresAt <= now) return CredentialCheck.Invalid("expired")
    if (person && expiresAt - event.createdAt > PERSON_CREDENTIAL_MAX_SECONDS) return CredentialCheck.Invalid("longer than 30 days")

    val device = event.tagValue("device") ?: return CredentialCheck.Invalid("no device")

    if (!Events.verify(event)) return CredentialCheck.Invalid("bad signature")

    // A credential is one of the places a device/participant pubkey enters
    // the system - the `device` tag in particular is free text set by
    // whoever minted the credential. Canonicalise both here so every caller
    // (roster decode, secondary-device adoption) compares against something
    // already lower-case, rather than each having to know to.
    return CredentialCheck.Valid(participant = event.pubkey.normaliseHex(), device = device.normaliseHex())
}
