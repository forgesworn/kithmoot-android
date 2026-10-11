package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PreparedChatOutboxTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        var writes = 0
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) {
            check(!fail) { "Synthetic storage failure" }
            bytes = value.clone(); writes++
        }
        override fun reset() { bytes = null }
    }
    private fun prepared(seed: Int = 1): PendingChatOutbox.Pending {
        val event = Events.sign(ByteArray(32) { 1 }, KIND_CHAT, 1000,
            listOf(listOf("d", "synthetic-epoch")), "synthetic ciphertext $seed", ByteArray(32))
        return PendingChatOutbox.Pending("synthetic-epoch", event, editable = false,
            text = "Synthetic recording", messageId = "synthetic-message-$seed")
    }
    private fun outbox(store: Store) = PendingChatOutbox(store, "synthetic-room", "synthetic-account", prepared().event.pubkey)

    @Test fun `reopened handoff reuses exact event and preserves ambiguous delivery without another write`() = runBlocking {
        val store = Store(); val item = prepared(); val first = outbox(store)
        assertEquals(item, first.retainPrepared(item))
        first.begin(item.event.id)
        val writes = store.writes
        val reopened = outbox(store)
        var guarded = false
        val retained = reopened.retainPrepared(item) { commit -> guarded = true; commit() }
        assertTrue(guarded)
        assertEquals(PendingChatState.UNKNOWN, retained.state)
        assertEquals(item.event, retained.event)
        assertEquals(writes, store.writes)
        assertEquals(listOf(retained), reopened.items())
        reopened.setState(item.event.id, PendingChatState.MOVED, force = true)
        assertEquals(PendingChatState.MOVED, reopened.retainPrepared(item).state)
    }

    @Test fun `retry after confirmation and interrupted draft cleanup still retains the identical relay event`() = runBlocking {
        val store = Store(); val item = prepared(); val first = outbox(store)
        first.retainPrepared(item); first.confirm(item.event.id)
        assertTrue(first.items().isEmpty())
        val restored = outbox(store).retainPrepared(item)
        assertEquals(item, restored)
        assertEquals(item.event.id, restored.event.id)
        assertEquals(item.messageId, restored.messageId)
    }

    @Test fun `full outbox permits exact handoff retry but rejects metadata substitution and a new event`() = runBlocking {
        val store = Store(); val first = outbox(store)
        for (i in 1..PendingChatOutbox.MAX_ITEMS) first.retainPrepared(prepared(i))
        assertEquals(prepared(), first.retainPrepared(prepared()))
        assertFailsWith<IllegalArgumentException> { first.retainPrepared(prepared().copy(text = "Substituted recording")) }
        assertFailsWith<IllegalArgumentException> { first.retainPrepared(prepared().copy(epochId = "different-epoch")) }
        assertFailsWith<IllegalStateException> { first.retainPrepared(prepared(99)) }
        assertEquals(PendingChatOutbox.MAX_ITEMS, first.items().size)
    }

    @Test fun `revoked or uncommitted owner and failed storage cannot present a successful handoff`() = runBlocking {
        val store = Store(); val first = outbox(store); val item = prepared()
        assertFailsWith<IllegalStateException> { first.retainPrepared(item) { error("Original draft forgotten") } }
        assertFailsWith<IllegalStateException> { first.retainPrepared(item) { } }
        assertTrue(first.items().isEmpty()); assertEquals(0, store.writes)
        store.fail = true
        assertFailsWith<IllegalStateException> { first.retainPrepared(item) }
        assertTrue(outbox(store).items().isEmpty())
        store.fail = false
        assertEquals(item, first.retainPrepared(item))
        assertFailsWith<IllegalArgumentException> { first.retainPrepared(item.copy(state = PendingChatState.UNKNOWN)) }
        assertEquals(listOf(item), first.items())
    }
}
