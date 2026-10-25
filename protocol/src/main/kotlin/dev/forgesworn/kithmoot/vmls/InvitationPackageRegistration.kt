package dev.forgesworn.kithmoot.vmls

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Ed25519Strict
import java.nio.ByteBuffer

/** Private keeper handoff. It proves one observed registration, never node
 * rights, current retention or MLS admission. No invitation/identity travels. */
object InvitationPackageRegistration {
    const val REQUEST_BYTES = 137
    const val RECEIPT_BYTES = 201
    private val DOMAIN = "Vennel/invitation/1 package registration\u0000".toByteArray()

    fun request(node: ByteArray, installation: ByteArray, packageId: ByteArray, mailbox: ByteArray, expiry: Long): ByteArray {
        require(listOf(node, installation, packageId, mailbox).all { it.size == 32 })
        require(expiry in 1..LeafBinding.MAX_SAFE)
        return ByteBuffer.allocate(REQUEST_BYTES).put(1).put(node).put(installation).put(packageId).put(mailbox).putLong(expiry).array()
    }
    fun readRequest(bytes: ByteArray): PackageRegistrationRequest {
        require(bytes.size == REQUEST_BYTES && bytes[0] == 1.toByte())
        val b = ByteBuffer.wrap(bytes)
        b.get()
        fun key() = ByteArray(32).also { b.get(it) }
        val node = key(); val installation = key(); val packageId = key(); val mailbox = key(); val expiry = b.long
        require(expiry in 1..LeafBinding.MAX_SAFE)
        return PackageRegistrationRequest(node, installation, packageId, mailbox, expiry)
    }
    fun verifyReceipt(receipt: ByteArray, expectedRequest: ByteArray): Boolean {
        if (receipt.size != RECEIPT_BYTES || expectedRequest.size != REQUEST_BYTES || !receipt.copyOfRange(0, REQUEST_BYTES).contentEquals(expectedRequest)) return false
        val request = try { readRequest(expectedRequest) } catch (_: IllegalArgumentException) { return false }
        return Ed25519Strict.verifyStrict(receipt.copyOfRange(REQUEST_BYTES, RECEIPT_BYTES), Digests.sha256(DOMAIN + expectedRequest), request.node)
    }
}

class PackageRegistrationRequest(val node: ByteArray, val installation: ByteArray, val packageId: ByteArray, val mailbox: ByteArray, val expiry: Long)
