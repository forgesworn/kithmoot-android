package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.*

class RoomSharingSelectionTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail: (ByteArray) -> Boolean = { false }
        var afterCommit = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) {
            if (fail(value) && !afterCommit) error("fault before commit")
            bytes = value.clone()
            if (fail(value)) error("fault after commit")
        }
        override fun reset() { error("A sharing edit cannot reset a store") }
    }
    private val room = "11".repeat(32)
    private val participant = "22".repeat(32)
    private val device = "33".repeat(32)
    private fun binding(author: String = "44", relay: String = "wss://fixture.invalid/") =
        RoomForwardingBinding(room, participant, device, "55".repeat(32), listOf(relay), setOf(author.repeat(32)))
    private fun selection(store: Store) = RoomSharingSelection(store, room, participant, device)
    private fun event(i: Int = 0) = Events.sign(ByteArray(32) { 4 }, KIND_CHAT, 1000,
        listOf(listOf("d", "66".repeat(32)), listOf("n", "$i")), "ciphertext", ByteArray(32))
    private fun RoomForwardingLedger.current() = bind(room, participant, device) { _, _ -> ForwardingVerdict.CURRENT }

    @Test fun `first creation commits consent before journal and completion before returning authority`() {
        val prefs = Store(); val journal = Store(); val selection = selection(prefs)
        var initialWrites = 0
        journal.fail = { assertFalse(selection.read()!!.initialised); initialWrites++; false }
        selection.prepare(binding(), journal) { 1_000_000 }.use { ledger ->
            assertTrue(initialWrites > 0)
            journal.fail = { false }
            assertTrue(selection.read()!!.initialised); assertTrue(ledger.status().suspended)
            assertNull(ledger.reserve(event().id, ForwardingLane.INTERNET))
        }
        journal.fail = { false }
        selection.prepare(binding(), journal) { 1_000_000 }.use { assertTrue(it.status().suspended) }
    }

    @Test fun `failed initial intent cannot create a journal and ambiguous intent can resume safely`() {
        for (ambiguous in listOf(false, true)) {
            val prefs = Store(); val journal = Store(); val selection = selection(prefs)
            prefs.fail = { true }; prefs.afterCommit = ambiguous
            assertFails { selection.prepare(binding(), journal) { 1_000_000 } }
            assertNull(journal.bytes)
            prefs.fail = { false }
            selection.prepare(binding(), journal) { 1_000_000 }.use { assertTrue(it.status().suspended) }
        }
    }

    @Test fun `failure completing first use never grants authority and preserves a created journal`() {
        for (ambiguous in listOf(false, true)) {
            val prefs = Store(); val journal = Store(); val selection = selection(prefs)
            prefs.fail = { it.toString(Charsets.UTF_8).contains("\"initialised\":true") }; prefs.afterCommit = ambiguous
            assertFails { selection.prepare(binding(), journal) { 1_000_000 } }
            assertNotNull(journal.bytes)
            prefs.fail = { false }
            selection.prepare(binding(), journal) { 1_000_000 }.use { assertTrue(it.status().suspended) }
        }
    }

    @Test fun `ordinary resume rejects missing or corrupt journal instead of granting fresh credit`() {
        val prefs = Store(); val journal = Store(); val selection = selection(prefs)
        selection.prepare(binding(), journal) { 1_000_000 }.close()
        journal.bytes = null
        assertFails { selection.prepare(binding(), journal) { 1_000_000 } }
        assertNull(journal.bytes)
        journal.bytes = "broken".toByteArray()
        assertFails { selection.prepare(binding(), journal) { 1_000_000 } }
        assertEquals("broken", journal.bytes!!.toString(Charsets.UTF_8))
    }

    @Test fun `explicit changes strand old events and preserve clock expiry UNKNOWN and lane debt`() {
        val prefs = Store(); val journal = Store(); val selection = selection(prefs); val e = event()
        selection.prepare(binding(), journal) { 1_000_000 }.use { ledger ->
            ledger.current(); ledger.observe(e, ForwardingLane.NEARBY); ledger.reserve(e.id, ForwardingLane.INTERNET)
        }
        val next = binding("77", "wss://next.invalid/")
        selection.prepare(next, journal) { 999_000 }.use { ledger ->
            val state = ledger.status(); assertTrue(state.suspended); assertEquals(1_000_000L, state.high)
            assertTrue(state.internetBytes > 0)
            val old = state.entries.single(); assertTrue(old.moved); assertEquals(e, old.event)
            assertEquals(ForwardingLaneState.UNKNOWN, old.internet.state); assertEquals(1, old.internet.attempts)
            assertEquals(1_000_000 + RoomForwardingLedger.TTL_MS, old.expires)
            ledger.current(); assertNull(ledger.reserve(e.id, ForwardingLane.INTERNET))
            assertEquals(ForwardingObservation.REFUSED, ledger.observe(e, ForwardingLane.NEARBY))
            assertEquals(ForwardingObservation.RETAINED, ledger.observe(event(1), ForwardingLane.NEARBY))
        }
        assertEquals(next.pin, selection.read()!!.current.pin); assertNull(selection.read()!!.pending)
        selection.prepare(binding(), journal) { 1_000_000 }.use { ledger ->
            ledger.current(); assertNull(ledger.reserve(e.id, ForwardingLane.INTERNET), "Changing back cannot revive an old export")
            assertTrue(ledger.status().internetBytes > 0)
        }
    }

    @Test fun `before and after migration commit faults recover the actually committed pin without export`() {
        for (ambiguous in listOf(false, true)) {
            val prefs = Store(); val journal = Store(); val selection = selection(prefs); val e = event()
            selection.prepare(binding(), journal) { 1_000_000 }.use { ledger ->
                ledger.current(); ledger.observe(e, ForwardingLane.NEARBY); ledger.reserve(e.id, ForwardingLane.INTERNET)
            }
            val next = binding("77")
            journal.fail = { it.toString(Charsets.UTF_8).contains(next.pin) }; journal.afterCommit = ambiguous
            assertFails { selection.prepare(next, journal) { 1_000_000 } }
            assertEquals(next.pin, selection.read()!!.pending!!.pin)
            journal.fail = { false }
            selection.prepare(next, journal) { 1_000_000 }.use { ledger ->
                val state = ledger.status(); assertTrue(state.suspended); assertTrue(state.internetBytes > 0)
                assertTrue(state.entries.single().moved); assertEquals(ForwardingLaneState.UNKNOWN, state.entries.single().internet.state)
            }
            assertEquals(next.pin, selection.read()!!.current.pin); assertNull(selection.read()!!.pending)
        }
    }

    @Test fun `ambiguous final preferences commit is resolved against journal without replacing queue debt`() {
        for (ambiguous in listOf(false, true)) {
            val prefs = Store(); val journal = Store(); val selection = selection(prefs)
            selection.prepare(binding(), journal) { 1_000_000 }.use { ledger ->
                ledger.current(); ledger.observe(event(), ForwardingLane.NEARBY); ledger.reserve(event().id, ForwardingLane.INTERNET)
            }
            val next = binding("77")
            prefs.fail = { it.toString(Charsets.UTF_8).contains("\"senders\":[\"${"77".repeat(32)}\"]") && !it.toString(Charsets.UTF_8).contains("\"pending\"") }
            prefs.afterCommit = ambiguous
            assertFails { selection.prepare(next, journal) { 1_000_000 } }
            prefs.fail = { false }
            selection.prepare(next, journal) { 1_000_000 }.use { ledger ->
                assertTrue(ledger.status().suspended); assertTrue(ledger.status().internetBytes > 0)
                assertTrue(ledger.status().entries.single().moved)
            }
        }
    }

    @Test fun `active owner cannot have selection changed and another identity cannot open preferences`() {
        val prefs = Store(); val journal = Store(); val selection = selection(prefs)
        selection.prepare(binding(), journal) { 1_000_000 }.use { ledger ->
            ledger.current(); val before = prefs.bytes!!.clone()
            assertFails { selection.prepare(binding("77"), journal) { 1_000_000 } }
            assertContentEquals(before, prefs.bytes)
            assertFails { ledger.changeSelection(binding("77")) }
        }
        assertFails { RoomSharingSelection(prefs, room, participant, "88".repeat(32)).read() }
    }

    @Test fun `withdrawing every person persists an empty disabled selection without reviving queued exports`() {
        val prefs = Store(); val journal = Store(); val selection = selection(prefs); val e = event()
        selection.prepare(binding(), journal) { 1_000_000 }.use { ledger ->
            ledger.current(); ledger.observe(e, ForwardingLane.NEARBY); ledger.reserve(e.id, ForwardingLane.INTERNET)
        }
        val empty = RoomForwardingBinding(room, participant, device, "55".repeat(32), binding().relays, emptySet())
        selection.prepare(empty, journal) { 1_000_000 }.use {
            assertTrue(it.status().suspended); assertTrue(it.status().entries.single().moved)
            assertTrue(it.status().internetBytes > 0)
        }
        assertTrue(selection.read()!!.current.senders.isEmpty())
        selection.prepare(binding(), journal) { 1_000_000 }.use {
            it.current(); assertNull(it.reserve(e.id, ForwardingLane.INTERNET))
        }
    }
}
