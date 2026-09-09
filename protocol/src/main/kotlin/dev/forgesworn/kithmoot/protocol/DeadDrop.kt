package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * Dead-drop key derivation, written from the `nostr-deaddrop` README and
 * checked against its known-answer vectors. This is the second
 * implementation of the derivation, kept apart from the first on purpose:
 * where the two disagree, the vectors decide.
 *
 * A drop key is one epoch, one direction, one counter:
 *
 *     scalar = HKDF-SHA256(ikm, salt = "nostr-deaddrop/v1",
 *                          info = "drop" || 0x00 || u64be(epoch) || sender || u16be(counter), 32) mod n
 *
 * The ikm for a pair is forgesworn-link's rendezvous material,
 * `case_byte || static_x || eph_x` (65 bytes); for a room it is
 * `0x10 || HKDF-SHA256(roomKey, "nostr-deaddrop/room/v1", "ikm", 32) || 32 zero bytes`.
 * The sender is the x-only key of whoever sends on the key, so the two
 * directions of a pair use different keys; the counter makes every drop in
 * an epoch its own key, so a relay never sees a tag twice.
 */
object DeadDrop {

    const val SALT: String = "nostr-deaddrop/v1"
    const val ROOM_SALT: String = "nostr-deaddrop/room/v1"
    const val EPOCH_SECONDS: Long = 3600
    const val MAX_PER_EPOCH_PAIR: Int = 64
    const val MAX_PER_EPOCH_ROOM: Int = 16
    const val ROOM_CASE_BYTE: Int = 0x10

    /** secp256k1 group order. */
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    enum class Case(val byte: Int) { NONE(0), ONE(1), BOTH(2), ROOM(0x10) }

    class DropKey(val privateKey: ByteArray, val publicKey: String, val epochIndex: Long, val counter: Int, val case: Case)

    class Ikm(val bytes: ByteArray, val case: Case)

    fun epochIndexAt(unixSeconds: Long, epochSeconds: Long = EPOCH_SECONDS): Long {
        require(epochSeconds > 0) { "bad clock" }
        return Math.floorDiv(unixSeconds, epochSeconds)
    }

    /**
     * A pair's ikm, byte for byte forgesworn-link's: the static ECDH x, then
     * the ephemeral x under whichever case the material allows. One side
     * carrying an ephemeral mixes it against the other's static key.
     */
    fun pairIkm(
        myPrivateKey: ByteArray,
        peerPublicKey: String,
        myEphemeralPrivateKey: ByteArray? = null,
        peerEphemeralPublicKey: String? = null,
    ): Ikm {
        val staticX = Schnorr.sharedPointX(myPrivateKey, xOnly(peerPublicKey))
        var ephX = ByteArray(32)
        var case = Case.NONE
        if (myEphemeralPrivateKey != null && peerEphemeralPublicKey != null) {
            ephX = Schnorr.sharedPointX(myEphemeralPrivateKey, xOnly(peerEphemeralPublicKey))
            case = Case.BOTH
        } else if (myEphemeralPrivateKey != null) {
            ephX = Schnorr.sharedPointX(myEphemeralPrivateKey, xOnly(peerPublicKey))
            case = Case.ONE
        } else if (peerEphemeralPublicKey != null) {
            ephX = Schnorr.sharedPointX(myPrivateKey, xOnly(peerEphemeralPublicKey))
            case = Case.ONE
        }
        val ikm = ByteArray(65)
        ikm[0] = case.byte.toByte()
        staticX.copyInto(ikm, 1)
        ephX.copyInto(ikm, 33)
        return Ikm(ikm, case)
    }

    /** A room's ikm, the same 65-byte shape under its own case byte so it can never collide with a pair's. */
    fun roomIkm(roomKey: ByteArray): Ikm {
        require(roomKey.size == 32) { "room key must be 32 bytes" }
        val ikm = ByteArray(65)
        ikm[0] = ROOM_CASE_BYTE.toByte()
        Digests.hkdfSha256(roomKey, ROOM_SALT.toByteArray(Charsets.UTF_8), "ikm".toByteArray(Charsets.UTF_8), 32).copyInto(ikm, 1)
        return Ikm(ikm, Case.ROOM)
    }

    fun deriveDropKey(ikm: Ikm, epochIndex: Long, sender: String, counter: Int = 0): DropKey {
        require(ikm.bytes.size == 65) { "ikm must be 65 bytes" }
        require(epochIndex >= 0) { "epoch index must be a non-negative integer" }
        require(counter in 0..0xffff) { "counter must be an integer from 0 to 65535" }
        require(HEX64.matches(sender)) { "sender must be a 32-byte x-only public key as lower-case hex" }
        val info = ByteBuffer.allocate(4 + 1 + 8 + 32 + 2)
        info.put("drop".toByteArray(Charsets.US_ASCII))
        info.put(0)
        info.putLong(epochIndex)
        info.put(sender.hexToBytes())
        info.putShort(counter.toShort())
        val salt = SALT.toByteArray(Charsets.UTF_8)
        var infoBytes = info.array()
        var scalar = BigInteger(1, Digests.hkdfSha256(ikm.bytes, salt, infoBytes, 32)).mod(N)
        var retry = 0
        while (scalar.signum() == 0) {
            retry += 1
            infoBytes = info.array() + byteArrayOf(retry.toByte())
            scalar = BigInteger(1, Digests.hkdfSha256(ikm.bytes, salt, infoBytes, 32)).mod(N)
        }
        val privateKey = scalar.toByteArray().let { raw ->
            val out = ByteArray(32)
            val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
            src.copyInto(out, 32 - src.size)
            out
        }
        return DropKey(privateKey, Schnorr.publicKeyHex(privateKey), epochIndex, counter, ikm.case)
    }

    /** Every key one sender may use in one epoch. */
    fun deriveDropEpoch(ikm: Ikm, epochIndex: Long, sender: String, max: Int): List<DropKey> =
        (0 until max).map { deriveDropKey(ikm, epochIndex, sender, it) }

    private fun xOnly(hex: String): ByteArray {
        require(HEX64.matches(hex)) { "expected 32-byte x-only public key as lower-case hex" }
        return hex.hexToBytes()
    }

    /** Lower-case hex of a derived public key, for callers matching `p` tags. */
    fun tagOf(key: DropKey): String = key.publicKey.lowercase().also { require(HEX64.matches(it)) }

    internal fun ByteArray.hexLower(): String = toHex()
}
