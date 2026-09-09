package dev.forgesworn.kithmoot.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification as libsodium and ed25519-dalek's
 * `verify_strict` check it, written out in full over BigInteger so that
 * what is checked is exactly what forgesworn-link SPEC §2.3 says and
 * nothing a library chose:
 *
 *  - the public key A and the nonce point R decode canonically (y below p);
 *  - neither A nor R is of small order;
 *  - S is below the group order L;
 *  - the cofactorless equation holds: [S]B = R + [k]A, k = SHA-512(R || A || M) mod L.
 *
 * A verifier that multiplies by the cofactor accepts an A or R carrying a
 * torsion component that this refuses, and two implementations would then
 * disagree on the same bytes. Affine arithmetic with an inversion per step
 * is slow by curve standards and plenty for one card.
 */
object Ed25519Strict {

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
    private val EIGHT: BigInteger = BigInteger.valueOf(8)

    /** An affine point on the twisted Edwards curve; (0, 1) is the identity. */
    class Point(val x: BigInteger, val y: BigInteger) {
        fun isIdentity(): Boolean = x.signum() == 0 && y == BigInteger.ONE
        override fun equals(other: Any?): Boolean = other is Point && x == other.x && y == other.y
        override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
    }

    val BASE: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0) ?: error("base point")
        Point(x, y)
    }

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    /** Decode 32 little-endian bytes into a point, or null when the encoding is not canonical or not on the curve. */
    fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val y = littleEndian(bytes).clearBit(255)
        if (y >= P) return null
        val sign = (bytes[31].toInt() ushr 7) and 1
        val x = recoverX(y, sign) ?: return null
        return Point(x, y)
    }

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        // x = u v^3 (u v^7)^((p-5)/8), then fix by sqrt(-1) if needed.
        val v3 = v.multiply(v).mod(P).multiply(v).mod(P)
        val uv7 = u.multiply(v3).mod(P).multiply(v3).mod(P).multiply(v).mod(P)
        var x = u.multiply(v3).mod(P).multiply(uv7.modPow(P.subtract(BigInteger.valueOf(5)).divide(EIGHT), P)).mod(P)
        val vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
        when {
            vx2 == u -> {}
            vx2 == P.subtract(u).mod(P) -> x = x.multiply(SQRT_M1).mod(P)
            else -> return null
        }
        if (x.signum() == 0 && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P)
        return x
    }

    fun add(a: Point, b: Point): Point {
        val x1y2 = a.x.multiply(b.y).mod(P)
        val y1x2 = a.y.multiply(b.x).mod(P)
        val y1y2 = a.y.multiply(b.y).mod(P)
        val x1x2 = a.x.multiply(b.x).mod(P)
        val dxxyy = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)
        val x3 = x1y2.add(y1x2).mod(P).multiply(BigInteger.ONE.add(dxxyy).modInverse(P)).mod(P)
        val y3 = y1y2.add(x1x2).mod(P).multiply(BigInteger.ONE.subtract(dxxyy).mod(P).modInverse(P)).mod(P)
        return Point(x3, y3)
    }

    fun multiply(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    fun isSmallOrder(point: Point): Boolean = multiply(point, EIGHT).isIdentity()

    fun verifyStrict(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val a = decode(publicKey) ?: return false
        val r = decode(signature.copyOfRange(0, 32)) ?: return false
        if (isSmallOrder(a) || isSmallOrder(r)) return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = MessageDigest.getInstance("SHA-512").run {
            update(signature, 0, 32)
            update(publicKey)
            update(message)
            digest()
        }
        val k = littleEndian(h).mod(L)
        return multiply(BASE, s) == add(r, multiply(a, k))
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())
}
