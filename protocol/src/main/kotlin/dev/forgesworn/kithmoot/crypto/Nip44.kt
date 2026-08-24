package dev.forgesworn.kithmoot.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.util.Base64

/**
 * NIP-44 v2, implemented here rather than borrowed from a Nostr SDK.
 *
 * The reason is the roster: it is encrypted to the **raw 32-byte room key**
 * used directly as the conversation key, with no ECDH keypair anywhere in
 * sight. Every Nostr SDK surface exposes only `encrypt(secretKey, publicKey)`
 * and offers no way to hand it a symmetric key, so the room channel cannot be
 * expressed through them at all.
 *
 * The construction itself is unchanged from the NIP: HKDF-SHA256 expand to
 * derive per-message keys, ChaCha20 (the raw stream cipher, not the AEAD) for
 * confidentiality, HMAC-SHA256 over nonce-as-AAD plus ciphertext for
 * authenticity, and NIP-44's power-of-two padding to blunt length analysis.
 * Only the primitives are ours to call - ChaCha20, HMAC and HKDF all come from
 * BouncyCastle.
 */
object Nip44 {

    const val VERSION: Int = 2

    private val CONVERSATION_KEY_SALT = "nip44-v2".toByteArray(Charsets.UTF_8)

    private const val MIN_PLAINTEXT_SIZE = 1
    private const val MAX_PLAINTEXT_SIZE = 65535

    /**
     * The conversation key for a pair of Nostr keys: HKDF-extract over the
     * shared ECDH point's x coordinate. Used for peer-to-peer gift wraps.
     */
    fun conversationKey(secretKey: ByteArray, peerPublicKey: ByteArray): ByteArray =
        Digests.hkdfExtractSha256(CONVERSATION_KEY_SALT, Schnorr.sharedPointX(secretKey, peerPublicKey))

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = Entropy.bytes(32)): String {
        require(conversationKey.size == 32) { "a conversation key is 32 bytes" }
        require(nonce.size == 32) { "a NIP-44 nonce is 32 bytes" }
        val keys = MessageKeys.derive(conversationKey, nonce)
        val padded = pad(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = chacha20(keys.chachaKey, keys.chachaNonce, padded)
        val mac = Digests.hmacSha256(keys.hmacKey, nonce + ciphertext)
        val payload = ByteArray(1 + nonce.size + ciphertext.size + mac.size)
        payload[0] = VERSION.toByte()
        nonce.copyInto(payload, 1)
        ciphertext.copyInto(payload, 1 + nonce.size)
        mac.copyInto(payload, 1 + nonce.size + ciphertext.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    /** Throws on any failure - callers that must not throw catch it themselves. */
    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(conversationKey.size == 32) { "a conversation key is 32 bytes" }
        require(payload.isNotEmpty()) { "empty NIP-44 payload" }
        require(payload[0] != '#') { "unsupported NIP-44 encoding" }
        val decoded = Base64.getDecoder().decode(payload)
        require(decoded.size >= 99) { "NIP-44 payload is too short" }
        require(decoded[0].toInt() == VERSION) { "unsupported NIP-44 version" }

        val nonce = decoded.copyOfRange(1, 33)
        val ciphertext = decoded.copyOfRange(33, decoded.size - 32)
        val mac = decoded.copyOfRange(decoded.size - 32, decoded.size)

        val keys = MessageKeys.derive(conversationKey, nonce)
        val expected = Digests.hmacSha256(keys.hmacKey, nonce + ciphertext)
        require(MessageDigest.isEqual(expected, mac)) { "NIP-44 MAC does not match" }

        val padded = chacha20(keys.chachaKey, keys.chachaNonce, ciphertext)
        return unpad(padded)
    }

    private class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray) {
        companion object {
            fun derive(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
                val expanded = Digests.hkdfExpandSha256(conversationKey, nonce, 76)
                return MessageKeys(
                    chachaKey = expanded.copyOfRange(0, 32),
                    chachaNonce = expanded.copyOfRange(32, 44),
                    hmacKey = expanded.copyOfRange(44, 76),
                )
            }
        }
    }

    private fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }

    /** NIP-44's padding: a big-endian length prefix, then zeroes out to [paddedLength]. */
    internal fun pad(plaintext: ByteArray): ByteArray {
        val length = plaintext.size
        require(length in MIN_PLAINTEXT_SIZE..MAX_PLAINTEXT_SIZE) { "plaintext is out of range for NIP-44" }
        val padded = ByteArray(2 + paddedLength(length))
        padded[0] = (length ushr 8).toByte()
        padded[1] = (length and 0xff).toByte()
        plaintext.copyInto(padded, 2)
        return padded
    }

    internal fun unpad(padded: ByteArray): String {
        require(padded.size >= 2) { "padded plaintext is too short" }
        val length = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(length in MIN_PLAINTEXT_SIZE..MAX_PLAINTEXT_SIZE) { "invalid NIP-44 padding" }
        require(padded.size == 2 + paddedLength(length)) { "invalid NIP-44 padding" }
        return String(padded, 2, length, Charsets.UTF_8)
    }

    /** Pads to a power-of-two-derived chunk, with a floor of 32 bytes. */
    internal fun paddedLength(length: Int): Int {
        require(length > 0) { "cannot pad an empty plaintext" }
        if (length <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(length - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((length - 1) / chunk + 1)
    }
}
