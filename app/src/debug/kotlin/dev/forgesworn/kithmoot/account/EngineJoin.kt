package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.mls.VmlsBoxClient
import dev.forgesworn.vmls.ffi.VmlsBindingRequest
import dev.forgesworn.vmls.ffi.VmlsCapability
import dev.forgesworn.vmls.ffi.VmlsCapabilityRequest
import dev.forgesworn.vmls.ffi.VmlsEcdhRequest
import dev.forgesworn.vmls.ffi.VmlsException
import dev.forgesworn.vmls.ffi.VmlsIntroduction
import dev.forgesworn.vmls.ffi.VmlsSignRequest
import dev.forgesworn.vmls.ffi.VmlsStep
import dev.forgesworn.vmls.ffi.prepareCapability
import dev.forgesworn.vmls.ffi.prepareIntroduction

/**
 * Joining a group (P3-03b-3b-2), debug builds only. The engine asks; the
 * vault answers each request under the persona's consent; nothing here holds
 * a secret. Between the two sides, out of band: the guest's MLS device key
 * and rendezvous key go to the keeper, and the keeper's rendezvous key, the
 * counter and its box to the guest (P3-03b-3 decision 8).
 */
class EngineJoin(
    private val vault: MlsVault,
    private val sessions: EngineSessions,
    private val principal: String,
    private val consent: ConsentPrompt,
    /** The persona's rendezvous child, read afresh for each ECDH (the vault wipes it). */
    private val rendezvous: suspend () -> StoredRendezvousChild?,
) {
    /**
     * The guest's capability for [adderRz] at [counter]: a pending-join
     * session, created under the host, whose outbox holds the sealed
     * capability for the introduction mailbox. The driver deposits it at
     * [binding]'s home box, the keeper's.
     */
    suspend fun requestJoin(
        host: SessionHost<EngineSession>, persona: String, binding: VmlsBindingRequest,
        adderRz: ByteArray, counter: Long, expiresAt: Long, now: Long,
    ): Hosted<VmlsStep> {
        require(counter >= 0 && expiresAt > now)
        val pending = sessionCall { prepareCapability(sessions.platform, now.toULong(), VmlsCapabilityRequest(binding, expiresAt.toULong(), adderRz.copyOf(), counter.toULong())) }
        pending.use {
            val sign = pending.signRequest()
            val signature = sign(persona, sign)
            val shared = ecdh(persona, pending.ecdhRequest())
            try {
                return host.create(persona) {
                    val created = sessionCall { pending.complete(now.toULong(), sign.operation, signature, shared) }
                    val session = EngineSession(created.session)
                    // A step the host cannot take closes the session it never received.
                    val step = try { hostedStep(created.step) } catch (fault: Throwable) { session.close(); throw fault }
                    session to step
                }
            } finally {
                shared.fill(0)
            }
        }
    }

    /**
     * The keeper's introduction for a guest's [peerRz] at [counter]. Not
     * persisted and not witnessed: it is derived again from the same values
     * and a fresh ECDH. Its [VmlsIntroduction.mailbox] is fetched for the
     * guest's capability, opened with [VmlsIntroduction.openCapability].
     */
    suspend fun introduction(persona: String, peerRz: ByteArray, counter: Long, now: Long): VmlsIntroduction {
        require(counter >= 0)
        val pending = sessionCall { prepareIntroduction(sessions.platform, now.toULong(), peerRz.copyOf(), counter.toULong()) }
        pending.use {
            val request = pending.request()
            val shared = ecdh(persona, request)
            try {
                return sessionCall { pending.complete(now.toULong(), request.operation, shared) }
            } finally {
                shared.fill(0)
            }
        }
    }

    /** The vault's leaf binding signature for an engine sign request, asked under the persona's consent. */
    suspend fun sign(persona: String, request: VmlsSignRequest): ByteArray =
        JoinVaultCalls.sign(vault, principal, persona, consent, request.operation, request.body, request.digest, request.expiresAt.toLong())

    private suspend fun ecdh(persona: String, request: VmlsEcdhRequest): ByteArray =
        JoinVaultCalls.ecdh(vault, principal, persona, request.operation, request.peerRz, request.expiresAt.toLong(), rendezvous)
}

/**
 * A guest's capability the keeper may add: opened from [envelope] (fetched
 * at [introduction]'s mailbox), for the device the keeper granted, with an
 * expiry the box takes at [boxNow] (the box's clock, P3-03b-3 decision 13).
 * Null for anything else: the guest chose every field, so nothing here
 * throws on them. Register its package, then add it.
 */
fun admissible(introduction: VmlsIntroduction, envelope: ByteArray, grantedDevice: ByteArray, boxNow: Long, now: Long): VmlsCapability? {
    val capability = try { introduction.openCapability(now.toULong(), envelope) } catch (_: VmlsException) { return null }
    val info = capability.info()
    val expiresAt = info.expiresAt.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
    val fits = expiresAt != null && info.device.contentEquals(grantedDevice) && info.packageId.size == 32 && info.welcomeMailbox.size == 32 &&
        VmlsBoxClient.packageAcceptable(boxNow, expiresAt, VmlsBoxClient.packageCiphertext(info.packageId, info.welcomeMailbox))
    if (!fits) { capability.close(); return null }
    return capability
}

/** The keeper's Add of an opened capability, as a host step. Register its package at the box first (D5). */
fun addStep(session: EngineSession, now: Long, capability: VmlsCapability) = hostedStep(session.inner.add(now.toULong(), listOf(capability)))
