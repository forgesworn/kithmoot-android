package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.vmls.ffi.VmlsBindingRequest
import dev.forgesworn.vmls.ffi.VmlsCapability
import dev.forgesworn.vmls.ffi.VmlsCapabilityRequest
import dev.forgesworn.vmls.ffi.VmlsEcdhRequest
import dev.forgesworn.vmls.ffi.VmlsIntroduction
import dev.forgesworn.vmls.ffi.VmlsSignRequest
import dev.forgesworn.vmls.ffi.VmlsStep
import dev.forgesworn.vmls.ffi.prepareCapability
import dev.forgesworn.vmls.ffi.prepareIntroduction
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
                    val created = pending.complete(now.toULong(), sign.operation, signature, shared)
                    EngineSession(created.session) to hostedStep(created.step)
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

    private suspend fun sign(persona: String, request: VmlsSignRequest): ByteArray {
        val reply = vault.signLeafBindingV1(
            vault.context(principal, persona),
            buildJsonObject {
                put("v", 1)
                put("operation", request.operation.toHex())
                put("body", Base64.getEncoder().encodeToString(request.body))
                put("digest", request.digest.toHex())
                put("expires_at", request.expiresAt.toLong())
            },
            consent,
        )
        return when (reply) {
            is VaultResult.Ok -> reply.value.signature.hexToBytes()
            is VaultResult.Refused -> throw JoinRefusedException("signature", reply.refusal)
        }
    }

    private suspend fun ecdh(persona: String, request: VmlsEcdhRequest): ByteArray {
        val reply = vault.rendezvousEcdhV1(
            vault.context(principal, persona),
            buildJsonObject {
                put("v", 1)
                put("operation", request.operation.toHex())
                put("peer_rz", request.peerRz.toHex())
                put("expires_at", request.expiresAt.toLong())
            },
            rendezvous,
        )
        return when (reply) {
            is VaultResult.Ok -> reply.value.sharedX.hexToBytes()
            is VaultResult.Refused -> throw JoinRefusedException("rendezvous", reply.refusal)
        }
    }
}

/** The keeper's Add of an opened capability, as a host step. Register its package at the box first (D5). */
fun addStep(session: EngineSession, now: Long, capability: VmlsCapability) = hostedStep(session.inner.add(now.toULong(), listOf(capability)))

/** The vault refused a request the join needed; nothing was created. */
class JoinRefusedException(val what: String, val refusal: VaultRefusal) : Exception("The vault refused the join's $what: $refusal")
