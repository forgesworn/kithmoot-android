package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the "person credentials" cases in `src/credential.test.ts`. */
class PersonCredentialTest {
    private val now = 1_800_000_000L
    private val room = "a".repeat(64)
    private val participantSecretKey = ByteArray(32) { (it + 3).toByte() }
    private val participant = Schnorr.publicKeyHex(participantSecretKey)
    private val device = Schnorr.publicKeyHex(ByteArray(32) { (it + 60).toByte() })

    @Test
    fun personCredentialVerifiesForThePersonAndForARoomOnlyWhenAccepted() {
        val cred = createPersonCredential(participantSecretKey, device, expiresAt = now + 7 * 24 * 3600, label = "phone", createdAt = now)
        assertTrue(cred.tags.contains(listOf("d", participant)))
        assertTrue(cred.tags.contains(listOf("scope", "person")))
        assertEquals(CredentialCheck.Valid(participant, device), verifyPersonCredential(cred, participant, now))
        assertEquals(CredentialCheck.Invalid("person credential where a room credential was expected"), verifyDeviceCredential(cred, room, now))
        assertEquals(CredentialCheck.Valid(participant, device), verifyDeviceCredential(cred, room, now, acceptPerson = true))
        assertEquals(CredentialCheck.Invalid("wrong person"), verifyPersonCredential(cred, "f".repeat(64), now))
    }

    @Test
    fun roomCredentialIsNeverAPersonCredentialAndAScopedRoomCredentialIsRefused() {
        val roomCred = createDeviceCredential(participantSecretKey, device, room, expiresAt = now + 3600, createdAt = now)
        assertEquals(CredentialCheck.Invalid("not a person credential"), verifyPersonCredential(roomCred, participant, now))
        val scoped = Events.sign(
            secretKey = participantSecretKey, kind = KIND_DEVICE_CREDENTIAL, createdAt = now,
            tags = listOf(listOf("d", room), listOf("device", device), listOf("expiration", (now + 3600).toString()), listOf("scope", "person")),
            content = "",
        )
        assertTrue(verifyDeviceCredential(scoped, room, now) is CredentialCheck.Invalid)
    }

    @Test
    fun personCredentialMayNotRunMoreThanThirtyDays() {
        var threw = false
        try { createPersonCredential(participantSecretKey, device, expiresAt = now + PERSON_CREDENTIAL_MAX_SECONDS + 1, createdAt = now) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
        val long = Events.sign(
            secretKey = participantSecretKey, kind = KIND_DEVICE_CREDENTIAL, createdAt = now,
            tags = listOf(listOf("d", participant), listOf("device", device), listOf("expiration", (now + PERSON_CREDENTIAL_MAX_SECONDS + 1).toString()), listOf("scope", "person")),
            content = "",
        )
        assertEquals(CredentialCheck.Invalid("longer than 30 days"), verifyPersonCredential(long, participant, now))
    }
}
