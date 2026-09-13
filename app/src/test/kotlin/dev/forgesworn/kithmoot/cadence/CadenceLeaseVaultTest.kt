package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.CadenceReceipt
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CadenceLeaseVaultTest {
    @Test fun prepareExcludesBeforeNetworkAndSurvivesRestart() {
        val storage = MemoryStorage()
        val first = CadenceLeaseVault(storage)
        val prepared = first.prepare(plan(), 100)

        assertEquals(CadenceOwnership.CLIENT_EXCLUDED, prepared.ownership)
        assertEquals((0 until 8).toList(), CadenceLeaseVault(storage).reservedCounters(ROOM, DEVICE, 12))
        assertEquals("exact retry keeps original durable record", prepared, CadenceLeaseVault(storage).prepare(plan(), 101))
    }

    @Test fun overlappingRangeAndChangedRetryStayExcluded() {
        val vault = CadenceLeaseVault(MemoryStorage())
        vault.prepare(plan(), 100)

        assertThrows(IllegalArgumentException::class.java) { vault.prepare(plan().copy(requestBody = "{}"), 101) }
        assertThrows(IllegalArgumentException::class.java) {
            vault.prepare(plan().copy(leaseId = "44".repeat(16), requestId = "55".repeat(16), startEpoch = 13, endEpoch = 15), 101)
        }
        assertEquals((0 until 8).toList(), vault.reservedCounters(ROOM, DEVICE, 12))
    }

    @Test fun onlyMatchingReceiptTransfersAndEndsOwnership() {
        val storage = MemoryStorage()
        val vault = CadenceLeaseVault(storage)
        val prepared = vault.prepare(plan(), 100)
        val active = receipt("active")
        assertThrows(IllegalArgumentException::class.java) { vault.accept(prepared, active.copy(endEpoch = 99), 101) }

        val owned = vault.accept(prepared, active, 101)
        assertEquals(CadenceOwnership.BOX_OWNED, owned.ownership)
        assertEquals((0 until 8).toList(), vault.reservedCounters(ROOM, DEVICE, 12))
        val ended = vault.accept(owned, receipt("ended"), 102)
        assertEquals(CadenceOwnership.ENDED, ended.ownership)
        assertEquals(emptyList<Int>(), vault.reservedCounters(ROOM, DEVICE, 12))
        vault.forgetEnded(14)
        assertEquals(emptyList<StoredCadenceLease>(), CadenceLeaseVault(storage).all())
    }

    @Test fun damagedJournalFailsClosed() {
        val storage = MemoryStorage("{bad".toByteArray())
        assertThrows(RoomStorageException::class.java) { CadenceLeaseVault(storage).all() }
        assertEquals("{bad", storage.value!!.toString(Charsets.UTF_8))
    }

    @Test fun aStoredReceiptCannotReleaseAnotherRange() {
        val storage = MemoryStorage()
        val vault = CadenceLeaseVault(storage)
        val owned = vault.accept(vault.prepare(plan(), 100), receipt("ended"), 101)
        assertEquals(CadenceOwnership.ENDED, owned.ownership)
        storage.value = storage.value!!.toString(Charsets.UTF_8)
            .replaceFirst("\"endEpoch\":14", "\"endEpoch\":15")
            .toByteArray()
        assertThrows(RoomStorageException::class.java) { CadenceLeaseVault(storage).all() }
    }

    private fun plan() = CadenceLeasePlan(
        "a".repeat(52), ROOM, ROOM, 1, DEVICE, "22".repeat(16), 1, "11".repeat(16), "{\"v\":1}", 12, 14, 0, 8,
    )

    private fun receipt(state: String) = CadenceReceipt(
        "status", "22".repeat(16), 1, state, 100, 12, 14, 0, emptyList(), emptyList(),
    )

    private class MemoryStorage(initial: ByteArray? = null) : RoomStorage {
        var value = initial
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }

    private companion object {
        val ROOM = "42".repeat(32)
        val DEVICE = "dd".repeat(32)
    }
}
