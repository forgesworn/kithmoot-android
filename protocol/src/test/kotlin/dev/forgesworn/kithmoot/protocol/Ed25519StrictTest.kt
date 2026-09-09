package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Ed25519Strict
import dev.forgesworn.kithmoot.crypto.hexToBytes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519StrictTest {

    // RFC 8032 §7.1, test 1: the empty message.
    private val pk = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToBytes()
    private val sig = ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").hexToBytes()

    @Test
    fun aKnownSignatureVerifies() {
        assertTrue(Ed25519Strict.verifyStrict(sig, ByteArray(0), pk))
    }

    @Test
    fun aChangedMessageOrSignatureDoesNot() {
        assertFalse(Ed25519Strict.verifyStrict(sig, byteArrayOf(1), pk))
        val flipped = sig.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(Ed25519Strict.verifyStrict(flipped, ByteArray(0), pk))
    }

    @Test
    fun sAtOrAboveTheGroupOrderIsRefused() {
        // L = 2^252 + 27742317777372353535851937790883648493, little-endian.
        val l = "edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010".hexToBytes()
        val bad = sig.copyOfRange(0, 32) + l
        assertFalse(Ed25519Strict.verifyStrict(bad, ByteArray(0), pk))
    }

    @Test
    fun smallOrderAndNonCanonicalPointsAreRefused() {
        // The identity point (0, 1) is of small order; so is (0, -1).
        val identity = ("01" + "00".repeat(31)).hexToBytes()
        assertTrue(Ed25519Strict.isSmallOrder(Ed25519Strict.decode(identity)!!))
        assertFalse(Ed25519Strict.verifyStrict(identity + sig.copyOfRange(32, 64), ByteArray(0), pk))
        assertFalse(Ed25519Strict.verifyStrict(sig, ByteArray(0), identity))
        // y = p is not a canonical encoding.
        val nonCanonical = ("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f").hexToBytes()
        assertNull(Ed25519Strict.decode(nonCanonical))
        // A real key decodes, and is not of small order.
        val a = Ed25519Strict.decode(pk)
        assertNotNull(a)
        assertFalse(Ed25519Strict.isSmallOrder(a!!))
    }
}
