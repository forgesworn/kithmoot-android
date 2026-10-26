package dev.forgesworn.kithmoot.protocol

import java.util.Base64
import kotlinx.serialization.json.*

/** Public-only v1 handoff shared with the own-stack signer bridges. */
class RendezvousProvisionRequest(
    val expectedPersona: String, val targetDevice: String, val index: Long,
    nonceBytes: ByteArray, val expiresAt: Long,
) {
    val nonce: String = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes.copyOf())
    init {
        require(Regex("[0-9a-f]{64}").matches(expectedPersona) && Regex("[0-9a-f]{64}").matches(targetDevice))
        require(index in 0..0xffff_ffffL && nonceBytes.size == 16 && expiresAt in 1..LeafSafeTimestamp.MAX)
    }
    fun validAt(now: Long): Boolean = now > 0 && expiresAt > now && expiresAt - now <= RENDEZVOUS_PROVISION_MAX_SECONDS
    fun json(): String = buildJsonObject {
        put("v", 1); put("expectedPersona", expectedPersona); put("targetDevice", targetDevice)
        put("index", index); put("nonce", nonce); put("expiresAt", expiresAt)
    }.toString()
    fun params(): JsonArray = buildJsonArray { add(targetDevice); add(index); add(nonce); add(expiresAt) }
    companion object { const val METHOD = "heartwood_provision_rendezvous" }
}

private object LeafSafeTimestamp { const val MAX = 9_007_199_254_740_991L }
