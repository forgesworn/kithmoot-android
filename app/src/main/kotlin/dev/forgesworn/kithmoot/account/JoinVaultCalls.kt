package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The vault calls a join makes for the engine (P3-03b-3b-2), each followed by
 * the adapter's reply check (P3-02 E04, E06): a value reaches the engine only
 * if this vault made it, in the current generation, for exactly this request.
 * Debug builds' `EngineJoin` calls these; they hold no secret.
 */
object JoinVaultCalls {
    /** The vault's leaf binding signature for an engine sign request, asked under the persona's consent. */
    suspend fun sign(
        vault: MlsVault, principal: String, persona: String, consent: ConsentPrompt,
        operation: ByteArray, body: ByteArray, digest: ByteArray, expiresAt: Long,
    ): ByteArray {
        val request = buildJsonObject {
            put("v", 1)
            put("operation", operation.toHex())
            put("body", Base64.getEncoder().encodeToString(body))
            put("digest", digest.toHex())
            put("expires_at", expiresAt)
        }
        val reply = vault.signLeafBindingV1(vault.sessionContext(principal, persona), request, consent)
        return when (reply) {
            is VaultResult.Refused -> throw JoinRefusedException("signature", reply.refusal)
            is VaultResult.Ok -> when (val accepted = vault.acceptSignReply(request, reply.value)) {
                is VaultResult.Ok -> accepted.value.signature.hexToBytes()
                is VaultResult.Refused -> throw JoinRefusedException("signature", accepted.refusal)
            }
        }
    }

    /** The vault's ECDH shared x for an engine request, from the persona's rendezvous child. */
    suspend fun ecdh(
        vault: MlsVault, principal: String, persona: String,
        operation: ByteArray, peerRz: ByteArray, expiresAt: Long,
        rendezvous: suspend () -> StoredRendezvousChild?,
    ): ByteArray {
        val request: JsonObject = buildJsonObject {
            put("v", 1)
            put("operation", operation.toHex())
            put("peer_rz", peerRz.toHex())
            put("expires_at", expiresAt)
        }
        val reply = vault.rendezvousEcdhV1(vault.sessionContext(principal, persona), request, rendezvous)
        return when (reply) {
            is VaultResult.Refused -> throw JoinRefusedException("rendezvous", reply.refusal)
            is VaultResult.Ok -> when (val accepted = vault.acceptEcdhReply(request, reply.value)) {
                is VaultResult.Ok -> accepted.value.sharedX.hexToBytes()
                is VaultResult.Refused -> throw JoinRefusedException("rendezvous", accepted.refusal)
            }
        }
    }
}

/** The vault refused a request the join needed; nothing was created. */
class JoinRefusedException(val what: String, val refusal: VaultRefusal) : Exception("The vault refused the join's $what: $refusal")
