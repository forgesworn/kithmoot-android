package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.LinkCards
import dev.forgesworn.kithmoot.protocol.LinkVerdict
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * The public halves of a persona's witness pairing (P3-03b-2). A Link node id
 * is the Ed25519 public key of its 32-byte transport seed (forgesworn-link
 * `TransportKey::from_seed`), and is what the box sees as the writer.
 */
object WriterIdentity {
    /** The writer's Link node id for [seed]. */
    fun nodeId(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    /**
     * The box's Link node id from the paired route's card, checked again
     * against the card's own signature as of when it was verified at pairing.
     * Null for a card that no longer verifies.
     */
    fun boxNodeId(route: StoredLinkRoute): ByteArray? {
        val at = route.cardVerifiedAt.toLong()
        if (at < 0) return null
        val verdict = runCatching { LinkCards.verify(route.card, at) }.getOrNull() as? LinkVerdict.Ok ?: return null
        return hex(verdict.card.nodeId)
    }

    private fun hex(value: String): ByteArray? {
        if (!value.matches(HEX64)) return null
        return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}
