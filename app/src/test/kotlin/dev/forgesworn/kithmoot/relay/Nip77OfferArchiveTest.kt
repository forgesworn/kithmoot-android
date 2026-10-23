package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Nip77OfferArchiveTest {
    private val account = hex(1)
    private val room = hex(2)
    private val address = hex(3)
    private val key = ByteArray(32) { (it + 4).toByte() }

    @Test fun `returns only exact own compared event IDs in the requested room window`() {
        val archive = Nip77OfferArchive(MemoryStorage())
        val first = chat(100, "one")
        val second = chat(101, "two")
        archive.record(account, room, first)
        archive.record(account, room, second)

        assertEquals(listOf(second.id), archive.available(account, room, address, 100, 101, listOf(second.id)).map(NostrEvent::id))
        assertEquals(emptyList(), archive.available(account, room, address, 100, 101, listOf(hex(9))))
    }

    @Test fun `rejects tampered or wrong outer events before retention`() {
        val archive = Nip77OfferArchive(MemoryStorage())
        val good = chat(100)
        assertFailsWith<RoomStorageException> { archive.record(account, room, good.copy(content = "tampered")) }
        assertFailsWith<RoomStorageException> { archive.record(account, room, Events.sign(key, 1, 100, listOf(listOf("d", address)), "wrong kind")) }
    }

    @Test fun `forgetting a room takes its events and leaves every other room's`() {
        val disk = MemoryStorage()
        val archive = Nip77OfferArchive(disk)
        val kept = chat(100, "kept")
        val gone = chat(101, "gone")
        archive.record(account, hex(9), kept)
        archive.record(account, room, gone)
        archive.forgetRoom(room)
        assertEquals(emptyList(), archive.available(account, room, address, 100, 101, listOf(gone.id)))
        assertEquals(listOf(kept.id), archive.available(account, hex(9), address, 100, 101, listOf(kept.id)).map(NostrEvent::id))
        archive.forgetRoom(hex(9))
        assertEquals(null, disk.value)
    }

    @Test fun `bounds one account to the newest 127 records`() {
        val archive = Nip77OfferArchive(MemoryStorage())
        val events = (0 until 130).map { chat(1_000L + it) }
        events.forEach { archive.record(account, room, it) }
        val returned = archive.available(account, room, address, 0, 30L * 24 * 60 * 60, events.drop(3).map(NostrEvent::id))
        assertEquals(127, returned.size)
        assertEquals(events[3].id, returned.first().id)
        assertEquals(events.last().id, returned.last().id)
    }

    @Test fun `recording a whole history writes once and nothing when it is already there`() {
        val disk = MemoryStorage()
        val archive = Nip77OfferArchive(disk)
        val history = (0 until 50).map { chat(1_000L + it, "message $it") }
        archive.recordAll(account, room, history)
        assertEquals(1, disk.writes)
        assertEquals(history.map(NostrEvent::id).toSet(),
            archive.available(account, room, address, 1_000, 1_049, history.map(NostrEvent::id)).map(NostrEvent::id).toSet())
        archive.recordAll(account, room, history)
        archive.record(account, room, history.first())
        assertEquals(1, disk.writes)
    }

    @Test fun `a batch leaves out an event that fails the checks and records the rest`() {
        val archive = Nip77OfferArchive(MemoryStorage())
        val good = chat(100)
        val tampered = chat(101).copy(content = "tampered")
        archive.recordAll(account, room, listOf(tampered, good))
        assertEquals(listOf(good.id), archive.available(account, room, address, 100, 101, listOf(good.id, tampered.id)).map(NostrEvent::id))
    }

    @Test fun `a batch keeps what recording each event in turn would keep`() {
        val day = 24L * 60 * 60
        val events = listOf(chat(1_000), chat(1_000 + 40 * day), chat(1_000 + 5 * day), chat(1_000 + 41 * day), chat(1_000 + 2 * day))
        val batch = MemoryStorage()
        val each = MemoryStorage()
        Nip77OfferArchive(batch).recordAll(account, room, events)
        Nip77OfferArchive(each).let { archive -> events.forEach { archive.record(account, room, it) } }
        kotlin.test.assertContentEquals(each.value, batch.value)
    }

    private fun chat(createdAt: Long, content: String = "ciphertext") =
        Events.sign(key, 1460, createdAt, listOf(listOf("d", address)), content)

    private fun hex(seed: Int): String = ByteArray(32) { (seed + it).toByte() }.toHex()

    private class MemoryStorage : RoomStorage {
        var value: ByteArray? = null
        var writes = 0
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { writes++; this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
