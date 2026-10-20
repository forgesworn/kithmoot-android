package dev.forgesworn.kithmoot.account

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DeviceBlocksTest {
    private val me = "aa".repeat(32)
    private val spammer = "bb".repeat(32)
    private val stranger = "cc".repeat(32)

    private class MemoryStorage : ProjectStorage {
        var value: String? = null
        var held = false
        override suspend fun acquire() { check(!held); held = true }
        override suspend fun load(): String? = value
        override suspend fun save(value: String) { check(held); this.value = value }
        override suspend fun release() { held = false }
    }

    @Test fun `blocks and unblocks, newest first, and survives a reopen`() = runTest {
        val storage = MemoryStorage()
        var clock = 1_000L
        val blocks = DeviceBlocks(me, storage) { clock++ }
        blocks.open()
        assertEquals(emptyList(), blocks.state.value)
        blocks.block(spammer); blocks.block(stranger)
        assertEquals(listOf(stranger, spammer), blocks.state.value.map { it.participant })
        assertTrue(blocks.contains(spammer))
        blocks.unblock(spammer)
        assertFalse(blocks.contains(spammer))
        blocks.close()
        assertFalse(blocks.contains(stranger))

        val again = DeviceBlocks(me, storage)
        again.open()
        assertEquals(listOf(DeviceBlock(stranger, 1_001)), again.state.value)
    }

    @Test fun `blocking somebody again moves them to the top, once`() = runTest {
        var clock = 1L
        val blocks = DeviceBlocks(me, MemoryStorage()) { clock++ }
        blocks.open()
        blocks.block(spammer); blocks.block(stranger); blocks.block(spammer)
        assertEquals(listOf(spammer, stranger), blocks.state.value.map { it.participant })
    }

    @Test fun `refuses yourself and anything that is not a key`() = runTest {
        val blocks = DeviceBlocks(me, MemoryStorage())
        blocks.open()
        assertFailsWith<IllegalArgumentException> { blocks.block(me) }
        assertFailsWith<IllegalArgumentException> { blocks.block("npub1spam") }
        assertFailsWith<IllegalArgumentException> { DeviceBlocks("me", MemoryStorage()) }
    }

    @Test fun `a stored list for another account, or unreadable entries, are not trusted`() {
        val text = encodeDeviceBlocks(me, listOf(DeviceBlock(spammer, 5)))
        assertEquals(listOf(DeviceBlock(spammer, 5)), parseDeviceBlocks(text, me))
        assertEquals(emptyList(), parseDeviceBlocks(text, stranger))
        assertEquals(emptyList(), parseDeviceBlocks("not json", me))
        val mixed = """{"v":1,"account":"$me","blocks":[{"p":"$spammer","at":2},{"p":"nope","at":3},{"p":"$stranger"},{"p":"$me","at":4}]}"""
        assertEquals(listOf(DeviceBlock(spammer, 2)), parseDeviceBlocks(mixed, me))
    }
}
