package dev.forgesworn.kithmoot.vmls

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class InvitationPackageRegistrationTest {
    private val secret = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
    private val node = secret.generatePublicKey().encoded
    private val installation = ByteArray(32) { 2 }
    private val packageId = ByteArray(32) { 3 }
    private val mailbox = ByteArray(32) { 4 }
    private val expiry = 1_900_000_000L
    private fun request() = InvitationPackageRegistration.request(node, installation, packageId, mailbox, expiry)
    private fun receipt(request: ByteArray): ByteArray {
        // Deliberately construct the reference signing preimage here rather
        // than calling the production verifier's private digest helper.
        val digest = Digests.sha256("Vennel/invitation/1 package registration\u0000".toByteArray() + request)
        val signature = Ed25519Signer().run { init(true, secret); update(digest, 0, digest.size); generateSignature() }
        return request + signature
    }
    @Test fun `independent Python reference verifies exact cross implementation receipt`() {
        val fixture = Json.parseToJsonElement(javaClass.getResourceAsStream("/invitation-package-registration-reference.json")!!.bufferedReader().use { it.readText() }).jsonObject
        fun hex(name: String) = fixture.getValue(name).jsonPrimitive.content.hexToBytes()
        val expected = InvitationPackageRegistration.request(hex("node"), hex("installation"), hex("package"), hex("mailbox"), fixture.getValue("expiry").jsonPrimitive.long)
        val receipt = hex("receiptHex")
        assertEquals(201, receipt.size)
        assertArrayEquals(expected, receipt.copyOfRange(0, 137))
        assertTrue(InvitationPackageRegistration.verifyReceipt(receipt, expected))
    }
    @Test fun `node signed receipt authenticates exact registration facts`() {
        val request = request()
        assertEquals(137, request.size)
        val facts = InvitationPackageRegistration.readRequest(request)
        assertArrayEquals(node, facts.node)
        assertArrayEquals(installation, facts.installation)
        assertArrayEquals(packageId, facts.packageId)
        assertArrayEquals(mailbox, facts.mailbox)
        assertEquals(expiry, facts.expiry)
        assertArrayEquals(ByteBuffer.allocate(8).putLong(expiry).array(), request.copyOfRange(129, 137))
        assertTrue(InvitationPackageRegistration.verifyReceipt(receipt(request), request))
    }
    @Test fun `wrong node installation package mailbox expiry signature and lengths refuse`() {
        val request = request()
        val receipt = receipt(request)
        for (offset in listOf(0, 1, 33, 65, 97, 129, 136)) {
            val other = request.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFalse("offset $offset", InvitationPackageRegistration.verifyReceipt(receipt, other))
        }
        assertFalse(InvitationPackageRegistration.verifyReceipt(receipt.copyOf().also { it[200] = (it[200].toInt() xor 1).toByte() }, request))
        assertFalse(InvitationPackageRegistration.verifyReceipt(receipt + byteArrayOf(0), request))
        assertFalse(InvitationPackageRegistration.verifyReceipt(receipt.copyOf(200), request))
        assertThrows(IllegalArgumentException::class.java) { InvitationPackageRegistration.readRequest(request + byteArrayOf(0)) }
    }
}
