package dev.forgesworn.kithmoot.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * BIP-340 signing and verification, plus the raw ECDH shared point NIP-44
 * needs. All of it delegates to libsecp256k1 via secp256k1-kmp - no curve
 * arithmetic is implemented here.
 */
object Schnorr {

    private val secp: Secp256k1 by lazy { Secp256k1.get() }

    /** The x-only (32-byte) public key for a secret key, as Nostr uses. */
    fun publicKey(secretKey: ByteArray): ByteArray {
        require(secretKey.size == 32) { "a secret key is 32 bytes" }
        return secp.pubkeyCreate(secretKey).copyOfRange(1, 33)
    }

    fun publicKeyHex(secretKey: ByteArray): String = publicKey(secretKey).toHex()

    /**
     * Signs a 32-byte message. [auxRand] is BIP-340's auxiliary randomness: it
     * is randomised in production as side-channel hardening, and pinned to a
     * fixed value by the interop vectors so a signature is reproducible.
     */
    fun sign(message: ByteArray, secretKey: ByteArray, auxRand: ByteArray = Entropy.bytes(32)): ByteArray {
        require(message.size == 32) { "BIP-340 signs a 32-byte message" }
        require(secretKey.size == 32) { "a secret key is 32 bytes" }
        require(auxRand.size == 32) { "aux-rand is 32 bytes" }
        return secp.signSchnorr(message, secretKey, auxRand)
    }

    /** Never throws: a malformed signature is simply not a valid one. */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean = try {
        signature.size == 64 &&
            message.size == 32 &&
            publicKey.size == 32 &&
            secp.verifySchnorr(signature, message, publicKey)
    } catch (_: Exception) {
        false
    }

    /**
     * The x coordinate of the shared ECDH point, which is what NIP-44 hashes
     * into a conversation key. This is deliberately not libsecp's `ecdh`,
     * which hashes the compressed point before returning it.
     */
    fun sharedPointX(secretKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(secretKey.size == 32) { "a secret key is 32 bytes" }
        require(peerPublicKey.size == 32) { "an x-only public key is 32 bytes" }
        // Nostr keys are x-only; lift to the even-y point before multiplying.
        val lifted = secp.pubkeyParse(byteArrayOf(0x02) + peerPublicKey)
        val shared = secp.pubKeyTweakMul(lifted, secretKey)
        return shared.copyOfRange(1, 33)
    }
}
