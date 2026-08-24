package dev.forgesworn.kithmoot.crypto

import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Hashes, MACs and HKDF, all taken from BouncyCastle rather than hand-rolled.
 * BouncyCastle ships on Android, so the same primitives back the app and these
 * plain-JVM tests.
 */
object Digests {

    fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = mac(HMac(SHA256Digest()), key, data)

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = mac(HMac(SHA1Digest()), key, data)

    private fun mac(mac: HMac, key: ByteArray, data: ByteArray): ByteArray {
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    /** RFC 5869 HKDF-SHA256: extract-then-expand. A null salt means all-zero. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    /** RFC 5869 HKDF-SHA256 extract only, which is HMAC(salt, ikm). */
    fun hkdfExtractSha256(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    /** RFC 5869 HKDF-SHA256 expand only, over an already-extracted PRK. */
    fun hkdfExpandSha256(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters.skipExtractParameters(prk, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }
}
