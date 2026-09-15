package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Nip77EventIndexTest {
    private val account = hex(1)
    private val otherAccount = hex(2)
    private val room = hex(3)
    private val address = hex(4)
    private val key = ByteArray(32) { (it + 5).toByte() }

    @Test fun recordsVerifiedOuterEventMetadataButNeverItsBody() {
        val disk = MemoryStorage()
        val index = Nip77EventIndex(disk)
        val event = chat(100, "private words must not enter the index")

        index.record(account, room, event)

        val records = index.records(account, room, address, 0, 200)
        assertEquals(1, records.size)
        assertEquals(100u, records.single().createdAt)
        assertContentEquals(event.id.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), records.single().id)
        assertTrue(!disk.value!!.decodeToString().contains(event.content))
        assertTrue(!disk.value!!.decodeToString().contains(event.sig))
    }

    @Test fun keepsAccountsRoomsAndChatAddressesDistinct() {
        val index = Nip77EventIndex(MemoryStorage())
        index.record(account, room, chat(100))
        index.record(otherAccount, room, chat(101))
        index.record(account, hex(9), chat(102))
        index.record(account, room, chat(103, address = hex(8)))

        assertEquals(1, index.records(account, room, address, 0, 200).size)
        assertEquals(1, index.records(otherAccount, room, address, 0, 200).size)
        assertEquals(0, index.records(account, room, hex(8), 0, 102).size)
        assertEquals(1, index.records(account, room, hex(8), 0, 200).size)
    }

    @Test fun refusesUnverifiedNonChatOrMalformedMetadata() {
        val index = Nip77EventIndex(MemoryStorage())
        val good = chat(100)

        assertFailsWith<RoomStorageException> {
            index.record(account, room, good.copy(content = "tampered"))
        }
        assertFailsWith<RoomStorageException> {
            index.record(account, room, Events.sign(key, 1, 100, listOf(listOf("d", address)), "not room chat"))
        }
        assertFailsWith<RoomStorageException> {
            index.record(account, room, Events.sign(key, 1460, 100, emptyList(), "missing address"))
        }
        assertEquals(emptyList(), index.records(account, room, address, 0, 200))
    }

    @Test fun windowIsBoundedAndOutputIsCappedToTheNewest127Events() {
        val index = Nip77EventIndex(MemoryStorage())
        repeat(130) { offset -> index.record(account, room, chat(1_000L + offset)) }

        val records = index.records(account, room, address, 0, 30L * 24 * 60 * 60)
        assertEquals(127, records.size)
        assertEquals(1_129u, records.first().createdAt)
        assertEquals(1_003u, records.last().createdAt)
        assertFailsWith<RoomStorageException> {
            index.records(account, room, address, 0, 30L * 24 * 60 * 60 + 1)
        }
    }

    @Test fun clearRemovesOneAccountWithoutExposingOrDeletingAnother() {
        val index = Nip77EventIndex(MemoryStorage())
        index.record(account, room, chat(100))
        index.record(otherAccount, room, chat(101))

        index.clear(account)

        assertEquals(emptyList(), index.records(account, room, address, 0, 200))
        assertEquals(1, index.records(otherAccount, room, address, 0, 200).size)
    }

    private fun chat(createdAt: Long, content: String = "ciphertext", address: String = this.address): NostrEvent =
        Events.sign(key, 1460, createdAt, listOf(listOf("d", address)), content)

    private fun hex(seed: Int): String = ByteArray(32) { (seed + it).toByte() }.toHex()

    private class MemoryStorage : RoomStorage {
        var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
