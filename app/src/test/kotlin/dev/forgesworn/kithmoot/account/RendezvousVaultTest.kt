package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.RendezvousProvisionExpect
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RendezvousVaultTest {
    private val identitySecret = key(1)
    private val identity = Schnorr.publicKeyHex(identitySecret)
    private val deviceSecret = key(2)
    private val device = Schnorr.publicKeyHex(deviceSecret)
    private val nonce = ByteArray(16) { it.toByte() }
    private val now = 1_793_577_600L

    @Test fun `accepts only an encrypted and fully bound child then returns copies`() {
        val storage = MemoryStorage()
        val vault = RendezvousVault(storage)
        val response = response(index = 7, scalar = key(3))

        val accepted = vault.accept(response, expect(), deviceSecret)

        assertTrue(accepted is RendezvousVaultResult.Accepted)
        assertEquals(7L, (accepted as RendezvousVaultResult.Accepted).receipt.index)
        val first = requireNotNull(vault.active(identity, device))
        val exposed = first.copyScalar()
        exposed.fill(0)
        val second = requireNotNull(vault.active(identity, device))
        assertContentEquals(key(3), second.copyScalar())
        assertTrue(first.toString().contains("index=7"))
        assertTrue(!first.toString().contains(key(3).toHex()))
    }

    @Test fun `refuses a wrapper whose public child is not the one that encrypted the record`() {
        val vault = RendezvousVault(MemoryStorage())
        val response = response(index = 7, scalar = key(3))
            .replace("\"rz\":\"${Schnorr.publicKeyHex(key(3))}\"", "\"rz\":\"${Schnorr.publicKeyHex(key(4))}\"")

        val result = vault.accept(response, expect(), deviceSecret)

        assertEquals("ciphertext", (result as RendezvousVaultResult.Refused).reason)
        assertNull(vault.active(identity, device))
    }

    @Test fun `rotation replaces only with a newer index and sign out erases it`() {
        val storage = MemoryStorage()
        val vault = RendezvousVault(storage)
        assertTrue(vault.accept(response(index = 7, scalar = key(3)), expect(), deviceSecret) is RendezvousVaultResult.Accepted)
        assertEquals("index", (vault.accept(response(index = 7, scalar = key(3)), expect(), deviceSecret) as RendezvousVaultResult.Refused).reason)
        assertTrue(vault.accept(response(index = 8, scalar = key(4)), expect(), deviceSecret) is RendezvousVaultResult.Accepted)
        assertEquals(8L, requireNotNull(vault.active(identity, device)).receipt.index)

        vault.clear(identity)

        assertNull(vault.active(identity, device))
        assertNull(storage.value)
    }

    @Test fun `corrupt vault fails closed and never silently creates another child`() {
        val storage = MemoryStorage(byteArrayOf(1, 2, 3))
        val vault = RendezvousVault(storage)

        assertFailsWith<RoomStorageException> { vault.active(identity, device) }
        assertContentEquals(byteArrayOf(1, 2, 3), storage.value)
    }

    private fun expect() = RendezvousProvisionExpect(identity, device, nonce.copyOf(), now)

    private fun response(index: Long, scalar: ByteArray): String {
        val rendezvous = Schnorr.publicKeyHex(scalar)
        val expiry = now + 300
        val nonceText = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        val record = """{"v":1,"p":"$identity","d":"$device","rz":"$rendezvous","u":"rendezvous","i":$index,"n":"$nonceText","e":$expiry,"k":"${Base64.getUrlEncoder().withoutPadding().encodeToString(scalar)}"}"""
        val conversation = Nip44.conversationKey(deviceSecret, rendezvous.hexToBytes())
        val ciphertext = try { Nip44.encrypt(record, conversation, ByteArray(32) { 7 }) } finally { conversation.fill(0) }
        return """{"v":1,"p":"$identity","d":"$device","rz":"$rendezvous","u":"rendezvous","i":$index,"n":"$nonceText","e":$expiry,"c":"$ciphertext"}"""
    }

    private fun key(value: Int) = ByteArray(32).also { it[31] = value.toByte() }

    private class MemoryStorage(initial: ByteArray? = null) : RoomStorage {
        var value = initial?.copyOf()
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
