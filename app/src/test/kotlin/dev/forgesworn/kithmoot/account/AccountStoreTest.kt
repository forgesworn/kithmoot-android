package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountStoreTest {
    private val work = NostrAccount("a".repeat(64), "nip55",
        signerPackage = Nip55.MY_SIGNET_PACKAGE, personaLabel = "Work")
    private val personal = NostrAccount("b".repeat(64), "nip55",
        signerPackage = Nip55.MY_SIGNET_PACKAGE, personaLabel = "Personal")

    @Test fun keepsTheLegacyConnectionAndRestoresTheLastSelectedPersona() {
        val storage = MemoryAccountStorage(work.toJson().toString().encodeToByteArray())
        val store = AccountStore(storage)
        assertEquals(work, store.load())
        store.save(personal)
        assertEquals(personal.pubkey, store.activePubkey.value)

        val reopened = AccountStore(storage)
        assertEquals(personal, reopened.load())
        assertEquals(listOf(personal, work), reopened.list())
        reopened.save(work)
        assertEquals(work, AccountStore(storage).load())
        assertEquals(listOf(work, personal), AccountStore(storage).list())

        reopened.clear()
        assertNull(reopened.activePubkey.value)
        assertNull(AccountStore(storage).load())
        assertEquals(listOf(personal), AccountStore(storage).list())
    }

    @Test fun aFailedWriteKeepsThePreviousPersonaActive() {
        val storage = MemoryAccountStorage()
        val store = AccountStore(storage)
        store.save(work)
        storage.refuseWrites = true

        assertThrows(RoomStorageException::class.java) { store.save(personal) }
        assertEquals(work.pubkey, store.activePubkey.value)
        assertEquals(work, AccountStore(storage).load())
        assertEquals(listOf(work), AccountStore(storage).list())
    }

    private class MemoryAccountStorage(private var bytes: ByteArray? = null) : RoomStorage {
        var refuseWrites = false
        override fun read(): ByteArray? = bytes
        override fun write(value: ByteArray) {
            check(!refuseWrites)
            bytes = value
        }
        override fun reset() { bytes = null }
    }
}
