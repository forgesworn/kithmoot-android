package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleProfileCacheTest {
    private class Memory : RoomStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(value: ByteArray) { bytes = value.copyOf() }
        override fun reset() { bytes = null }
    }
    private val account = "ab".repeat(32)
    private val event = Events.sign(ByteArray(32) { 1 }, 0, 100, emptyList(),
        """{"display_name":"Rowan","picture":"https://example.com/rowan.png"}""")

    @Test fun `verified names and picture URLs survive a new cache instance without a relay request`() = runTest {
        val storage = Memory()
        PeopleProfileCache(account, storage) { 100 }.put(listOf(event))
        val restored = PeopleProfileCache(account, storage) { 101 }
        val shown = mutableMapOf<String, PublicProfile>()
        loadPeopleProfiles(listOf(event.pubkey), restored, { true }, { error("Fresh cache must not open relays") }, { 101 }) { shown.putAll(it) }
        assertEquals("Rowan", shown[event.pubkey]?.name)
        assertEquals("https://example.com/rowan.png", shown[event.pubkey]?.picture)
        restored.clear()
        assertTrue(restored.read(listOf(event.pubkey)).profiles.isEmpty())
    }

    @Test fun `a cache cannot be restored as a different account`() {
        val storage = Memory()
        PeopleProfileCache(account, storage) { 100 }.put(listOf(event))
        assertFails { PeopleProfileCache("cd".repeat(32), storage) { 101 }.read(listOf(event.pubkey)) }
        assertNotNull(storage.bytes)
    }

    @Test fun `large signed profiles are evicted within the byte cap while remaining entries still restore`() {
        val storage = Memory()
        val cache = PeopleProfileCache(account, storage) { 100 }
        val events = (1..40).map { seed -> Events.sign(ByteArray(32) { seed.toByte() }, 0, 100,
            listOf(listOf("x", "z".repeat(60_000))), """{"name":"Contact $seed"}""") }
        cache.put(events)
        assertTrue(storage.bytes!!.size <= 2 * 1024 * 1024)
        val restored = cache.read(events.map { it.pubkey }).profiles
        assertTrue(restored.isNotEmpty())
        assertTrue(restored.size < events.size)
        assertTrue(restored.values.all { it.name?.startsWith("Contact ") == true })
    }

    @Test fun `forged wrong-kind future and oversized events cannot poison the cache`() {
        val cache = PeopleProfileCache(account, Memory()) { 100 }
        val wrongKind = Events.sign(ByteArray(32) { 2 }, 1, 100, emptyList(), "{}")
        val future = Events.sign(ByteArray(32) { 3 }, 0, 401, emptyList(), "{}")
        val oversized = Events.sign(ByteArray(32) { 4 }, 0, 100, listOf(listOf("x", "z".repeat(81_000))), "{}")
        cache.put(listOf(event, event.copy(content = """{"name":"Forged"}"""), wrongKind, future, oversized))
        assertEquals(setOf(event.pubkey), cache.read(listOf(event.pubkey, wrongKind.pubkey, future.pubkey, oversized.pubkey)).profiles.keys)
    }

    @Test fun `older relay results cannot replace or refresh newer cached metadata and equal timestamps use lowest id`() {
        var now = 110L
        val cache = PeopleProfileCache(account, Memory()) { now }
        val newer = Events.sign(ByteArray(32) { 1 }, 0, 101, emptyList(), """{"name":"New name"}""")
        val tie = Events.sign(ByteArray(32) { 1 }, 0, 101, emptyList(), """{"name":"Other name"}""")
        cache.put(listOf(tie, newer))
        val expected = listOf(newer, tie).minBy { it.id }
        now += PEOPLE_PROFILE_FRESH_SECONDS + 1
        cache.put(listOf(event))
        val restored = cache.read(listOf(event.pubkey))
        assertEquals(expected.id, restored.profiles[event.pubkey]?.eventId)
        assertTrue(restored.fresh.isEmpty())
    }

    @Test fun `stale cache remains visible during bounded offline retries and expires after thirty days`() = runTest {
        var now = 100L
        val cache = PeopleProfileCache(account, Memory()) { now }
        cache.put(listOf(event))
        now += PEOPLE_PROFILE_FRESH_SECONDS + 1
        var requests = 0
        val shown = mutableMapOf<String, PublicProfile>()
        loadPeopleProfiles(listOf(event.pubkey), cache, { true }, {
            requests++
            flow { awaitCancellation() }
        }, { now }) { shown.putAll(it) }
        assertEquals(3, requests)
        assertEquals(51_000L, currentTime)
        assertEquals("Rowan", shown[event.pubkey]?.name)
        now += 30 * PEOPLE_PROFILE_FRESH_SECONDS
        assertTrue(cache.read(listOf(event.pubkey)).profiles.isEmpty())
    }

    @Test fun `disabling profiles or changing account prevents cached display and late persistence`() = runTest {
        val storage = Memory()
        val cache = PeopleProfileCache(account, storage) { 100 }
        var current = false
        loadPeopleProfiles(listOf(event.pubkey), cache, { current }, { error("Disabled") }, { 100 }) { error("Disabled") }
        current = true
        loadPeopleProfiles(listOf(event.pubkey), cache, { current }, { flow { emit(event); awaitCancellation() } }, { 100 }) {
            if (it.isNotEmpty()) current = false
        }
        assertNull(storage.bytes)
    }

    @Test fun `an unreadable cache falls back to verified live profiles without deleting the store`() = runTest {
        val storage = Memory().apply { bytes = "broken".toByteArray() }
        val cache = PeopleProfileCache(account, storage) { 100 }
        val shown = mutableMapOf<String, PublicProfile>()
        loadPeopleProfiles(listOf(event.pubkey), cache, { true }, { flow { emit(event) } }, { 100 }) { shown.putAll(it) }
        assertEquals("Rowan", shown[event.pubkey]?.name)
        assertEquals("broken", storage.bytes?.decodeToString())
    }

    @Test fun `empty fast relay EOSE does not exclude a profile from a later relay connection`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://fast", "wss://slow"), sockets, backgroundScope, writeRelays = emptySet())
        pool.start(); runCurrent()
        val cache = PeopleProfileCache(account, Memory()) { 100 }
        val shown = mutableMapOf<String, PublicProfile>()
        try {
            val lookup = launch { loadPeopleProfiles(listOf(event.pubkey), cache, { true },
                { authors -> pool.subscribe(listOf(Filter(kinds = listOf(0), authors = authors, limit = authors.size * 2))) },
                { 100 }) { shown.putAll(it) } }
            runCurrent()
            val fast = sockets.forUrl("wss://fast").single()
            val slow = sockets.forUrl("wss://slow").single()
            fast.open(); runCurrent()
            val first = fast.requestedSubscriptions().single()
            fast.deliverRaw("""["EOSE","$first"]"""); runCurrent()
            assertFalse(lookup.isCompleted)
            advanceTimeBy(2_000); slow.open(); runCurrent()
            val later = slow.requestedSubscriptions().single()
            slow.deliverEvent(later, event); slow.deliverRaw("""["EOSE","$later"]"""); runCurrent()
            assertEquals("Rowan", shown[event.pubkey]?.name)
            advanceTimeBy(15_000); runCurrent(); lookup.join()
            assertEquals("Rowan", cache.read(listOf(event.pubkey)).profiles[event.pubkey]?.name)
            assertTrue(sockets.opened.all { it.publishedFrames().isEmpty() })
            assertTrue(sockets.opened.all { socket -> socket.sent.any { it.startsWith("[\"CLOSE\"") } })
        } finally { pool.stop() }
    }

    @Test fun `memory merge also refuses an older relay result`() {
        val current = PublicProfile("New", null, 101, "a")
        val old = PublicProfile("Old", null, 100, "b")
        assertEquals(current, mergedPeopleProfiles(mapOf(event.pubkey to current), mapOf(event.pubkey to old), 100)[event.pubkey])
    }
}
