package dev.forgesworn.kithmoot.account

import dev.forgesworn.vmls.ffi.VmlsException
import dev.forgesworn.vmls.ffi.VmlsPlatform
import dev.forgesworn.vmls.ffi.VmlsRandom
import dev.forgesworn.vmls.ffi.inspectAdminInvitation
import dev.forgesworn.vmls.ffi.inspectInvitation
import java.security.SecureRandom
import org.junit.Assert.assertThrows
import org.junit.Test

/** Safe on an installed minified release: no app storage, owner keys or network access. */
class VmlsNativeLoadTest {
    // The secp256k1 generator's public x coordinate; no private material is needed.
    private val publicKey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val random = object : VmlsRandom {
        private val source = SecureRandom()
        override fun fill(len: UInt) = ByteArray(len.toInt()).also(source::nextBytes)
    }

    @Test fun nativePlatformConstructorsLoadAndDispose() {
        VmlsPlatform(publicKey, publicKey, random).use { }
        VmlsPlatform.newWithRevocations(publicKey, publicKey, listOf(ByteArray(32) { 1 }), random).use { }
    }

    @Test fun invitationInspectionUsesRevocationAwareNativeSignatures() {
        val revoked = listOf(ByteArray(32) { 1 })
        assertThrows(VmlsException::class.java) { inspectInvitation(1uL, byteArrayOf(), revoked) }
        assertThrows(VmlsException::class.java) { inspectAdminInvitation(1uL, byteArrayOf(), revoked) }
    }
}
