package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Share my call relay here", kept per person and room on this device only. */
class SpryShareStoreTest {
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)
    private fun room(n: Int) = "%064x".format(n)

    @Test fun `a choice is kept per person and room, and turning it off forgets it`() {
        val storage = MemoryStorage()
        val store = SpryShareStore(storage)
        assertFalse(store.read(alice, room(1)))
        store.save(alice, room(1), true)
        assertTrue(store.read(alice, room(1)))
        assertFalse(store.read(bob, room(1)))
        assertFalse(store.read(alice, room(2)))
        // A new store over the same file reads the same choice: it survives a restart.
        assertTrue(SpryShareStore(storage).read(alice, room(1)))
        store.save(alice, room(2), true)
        store.save(alice, room(1), false)
        assertFalse(store.read(alice, room(1)))
        assertTrue(store.read(alice, room(2)))
        store.save(alice, room(2), false)
        // Nothing chosen leaves nothing behind.
        assertNull(storage.bytes)
    }

    @Test fun `at most 256 rooms, the oldest choice going first`() {
        val storage = MemoryStorage()
        val store = SpryShareStore(storage)
        for (n in 0 until 300) store.save(alice, room(n), true)
        assertFalse(store.read(alice, room(0)))
        assertFalse(store.read(alice, room(43)))
        assertTrue(store.read(alice, room(44)))
        assertTrue(store.read(alice, room(299)))
        assertEquals(256, SpryShareStore.decode(storage.bytes).size)
    }

    @Test fun `a damaged file shares nothing and an invalid room is refused`() {
        assertFalse(SpryShareStore(MemoryStorage("not json".toByteArray())).read(alice, room(1)))
        val mixed = MemoryStorage("[\"$alice:${room(1)}\", 7, \"short:room\", \"$alice:${room(1)}\"]".toByteArray())
        assertEquals(listOf("$alice:${room(1)}"), SpryShareStore.decode(mixed.bytes))
        assertTrue(SpryShareStore(mixed).read(alice, room(1)))
        assertFailsWith<IllegalArgumentException> { SpryShareStore(MemoryStorage()).save("not-a-key", room(1), true) }
        // Storage that cannot be read is not shared.
        val broken = object : dev.forgesworn.kithmoot.storage.RoomStorage {
            override fun read(): ByteArray? = throw java.io.IOException("locked")
            override fun write(value: ByteArray) = throw java.io.IOException("locked")
            override fun reset() {}
        }
        assertFalse(SpryShareStore(broken).read(alice, room(1)))
    }

    @Test fun `the row is hidden where a hand-entered call relay outranks Spry`() {
        assertEquals(true, spryShareRow(true, handEnteredRelay = false))
        assertEquals(false, spryShareRow(false, handEnteredRelay = false))
        // Not offered (unpaired, or a room this device made): hidden either way.
        assertNull(spryShareRow(null, handEnteredRelay = false))
        // Your own relay is what your calls use, so the row would offer nothing that is used.
        assertNull(spryShareRow(true, handEnteredRelay = true))
        assertNull(spryShareRow(false, handEnteredRelay = true))
    }
}
