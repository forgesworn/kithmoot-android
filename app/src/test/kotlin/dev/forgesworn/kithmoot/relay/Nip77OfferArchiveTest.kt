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

    @Test fun `bounds one account to the newest 127 records`() {
        val archive = Nip77OfferArchive(MemoryStorage())
        val events = (0 until 130).map { chat(1_000L + it) }
        events.forEach { archive.record(account, room, it) }
        val returned = archive.available(account, room, address, 0, 30L * 24 * 60 * 60, events.drop(3).map(NostrEvent::id))
        assertEquals(127, returned.size)
        assertEquals(events[3].id, returned.first().id)
        assertEquals(events.last().id, returned.last().id)
    }

    private fun chat(createdAt: Long, content: String = "ciphertext") =
        Events.sign(key, 1460, createdAt, listOf(listOf("d", address)), content)

    private fun hex(seed: Int): String = ByteArray(32) { (seed + it).toByte() }.toHex()

    private class MemoryStorage : RoomStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
