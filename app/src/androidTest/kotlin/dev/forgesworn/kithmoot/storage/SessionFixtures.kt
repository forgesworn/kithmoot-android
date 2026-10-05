package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.EngineSession
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.SignLeafBindingReply
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.vmls.ffi.VmlsBindingRequest
import dev.forgesworn.vmls.ffi.VmlsCredential
import dev.forgesworn.vmls.ffi.VmlsStep
import dev.forgesworn.vmls.ffi.prepareCreate
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Creates a lone-member group under [host] (P3-03b-3a): the engine prepares
 * it, the vault signs its leaf binding with the enrolled device, and the
 * host witnesses its first snapshot. [credential] is the device credential
 * the vault's enrolment signed.
 */
suspend fun createGroup(
    vault: MlsVault, host: SessionHost<EngineSession>, sessions: EngineSessions,
    persona: String, credential: NostrEvent, now: Long, random: SecureRandom = SecureRandom(),
): Hosted<VmlsStep> {
    val request = VmlsBindingRequest(
        VmlsCredential(credential.pubkey.hexToBytes(), credential.createdAt.toULong(), credential.tags, credential.content, credential.sig.hexToBytes()),
        ByteArray(32).also(random::nextBytes), (now + 3_600).toULong(),
    )
    val pending = prepareCreate(sessions.platform, now.toULong(), request, ByteArray(32).also(random::nextBytes))
    val sign = pending.request()
    val reply = vault.signLeafBindingV1(
        vault.context("dev.forgesworn.kithmoot", persona),
        buildJsonObject {
            put("v", 1)
            put("operation", sign.operation.toHex())
            put("body", Base64.getEncoder().encodeToString(sign.body))
            put("digest", sign.digest.toHex())
            put("expires_at", sign.expiresAt.toLong())
        },
        ConsentPrompt { ConsentDecision.Approve },
    )
    val signature = (reply as VaultResult.Ok<SignLeafBindingReply>).value.signature.hexToBytes()
    return host.create(persona) {
        val created = pending.complete(now.toULong(), sign.operation, signature)
        EngineSession(created.session) to hostedStep(created.step)
    }
}
