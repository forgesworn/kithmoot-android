package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EpochVaultTest {
    private val initial = ByteArray(32) { 7 }
    private val room = dev.forgesworn.kithmoot.protocol.deriveRoom(initial).roomId
    private val authority = "43".repeat(32)

    @Test fun `pending successor survives restart and only activates after cadence retirement`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        assertArrayEquals(initial, EpochVault(storage).get(room)?.currentSecret)

        val successor = ByteArray(32) { 9 }
        val notice = RekeyNotice(1, listOf("55".repeat(32)), null, false, successor, 101)
        val coordinate = CadenceEpochCoordinate("a".repeat(52), "11".repeat(16), 1, room, 1)
        val pending = vault.beginTransition(room, 0, notice, "aa".repeat(32), coordinate, 102)
        assertEquals(EpochPhase.PENDING_CADENCE_RETIREMENT, pending.phase)
        assertEquals(0, pending.currentEpoch)
        assertArrayEquals(initial, pending.currentSecret)
        assertArrayEquals(successor, pending.pending?.secret)
        assertEquals(coordinate, pending.pending?.cadence)

        val restarted = EpochVault(storage)
        val recovered = restarted.get(room)!!
        assertEquals(EpochPhase.PENDING_CADENCE_RETIREMENT, recovered.phase)
        assertArrayEquals(successor, recovered.pending?.secret)
        restarted.beginTransition(room, 0, notice, "aa".repeat(32), coordinate, 103)
        assertThrows(IllegalArgumentException::class.java) {
            restarted.beginTransition(
                room, 0, RekeyNotice(1, notice.removed, notice.by, notice.closed, ByteArray(32) { 8 }, notice.at),
                "aa".repeat(32), coordinate, 103,
            )
        }

        val active = restarted.activate(room, 1, 104)
        assertEquals(EpochPhase.ACTIVE, active.phase)
        assertEquals(1, active.currentEpoch)
        assertArrayEquals(successor, active.currentSecret)
        assertNull(active.pending)
        assertEquals(active.currentEpoch, EpochVault(storage).activate(room, 1, 105).currentEpoch)
        assertEquals(1, EpochVault(storage).initialise(room, authority, initial.copyOf(), 106).currentEpoch)
    }

    @Test fun `epoch zero conflicts rollbacks and terminal states fail closed`() {
        val storage = MemoryStorage()
        val vault = EpochVault(storage)
        vault.initialise(room, authority, initial, 100)
        vault.initialise(room, authority, initial.copyOf(), 101)
        assertThrows(IllegalArgumentException::class.java) {
            vault.initialise(dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 8 }).roomId, authority, initial, 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            vault.beginTransition(room, 0, RekeyNotice(2, emptyList(), null, false, ByteArray(32) { 9 }, 102), "aa".repeat(32), null, 102)
        }
        val removed = vault.terminal(
            room, 0, RekeyNotice(1, listOf("55".repeat(32)), null, false, null, 103), "cc".repeat(32), 103,
        )
        assertEquals(EpochPhase.REMOVED, removed.phase)
        assertEquals(listOf("55".repeat(32)), removed.removed)
        assertEquals(EpochPhase.REMOVED, vault.terminal(
            room, 0, RekeyNotice(1, listOf("55".repeat(32)), null, false, null, 103), "cc".repeat(32), 104,
        ).phase)
        assertThrows(IllegalArgumentException::class.java) {
            vault.beginTransition(room, 0, RekeyNotice(1, emptyList(), null, false, ByteArray(32) { 4 }, 104), "bb".repeat(32), null, 104)
        }
    }

    @Test fun `damaged journal is never replaced by an empty epoch set`() {
        val storage = MemoryStorage("{bad".toByteArray())
        assertThrows(RoomStorageException::class.java) { EpochVault(storage).get(room) }
        assertEquals("{bad", storage.value?.decodeToString())
    }

    private class MemoryStorage(initial: ByteArray? = null) : RoomStorage {
        var value = initial?.copyOf()
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
