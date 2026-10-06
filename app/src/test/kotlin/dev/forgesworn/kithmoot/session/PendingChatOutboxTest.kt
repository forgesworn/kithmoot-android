package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class PendingChatOutboxTest {
    private class MemoryStore : RoomStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    @Test fun exactEventSurvivesReopenAndOnlyMatchingConfirmationClearsIt() = runBlocking {
        val store = MemoryStore()
        val event = Events.sign(ByteArray(32) { 1 }, KIND_CHAT, 1_000,
            listOf(listOf("d", "room-epoch")), "ciphertext", ByteArray(32))
        val first = PendingChatOutbox(store, "room", "account", event.pubkey)
        first.retain("room-epoch", event)
        val reopened = PendingChatOutbox(store, "room", "account", event.pubkey)
        assertEquals(event, reopened.pending()?.event)
        reopened.confirm("another-event")
        assertNotNull(reopened.pending())
        reopened.confirm(event.id)
        assertNull(reopened.pending())
    }

    private fun chat(seed: Int, at: Long = 1_000L) = Events.sign(ByteArray(32) { 1 }, KIND_CHAT, at,
        listOf(listOf("d", "room-epoch"), listOf("n", "$seed")), "ciphertext $seed", ByteArray(32))

    @Test fun anOldSingleSlotJournalReadsAsOneItemThatMayHaveGone() = runBlocking {
        val store = MemoryStore()
        val event = chat(1)
        store.write(buildJsonObject {
            put("v", 1); put("room", "room"); put("participant", "account"); put("device", event.pubkey)
            put("epoch", "room-epoch"); put("event", event.toJson())
        }.toString().toByteArray())
        val outbox = PendingChatOutbox(store, "room", "account", event.pubkey)
        val item = outbox.items().single()
        assertEquals(event, item.event)
        assertEquals(PendingChatState.UNKNOWN, item.state)
        // The next write is in the new format and keeps it.
        outbox.retain("room-epoch", chat(2), text = "two")
        assertEquals(listOf(event.id, chat(2).id), outbox.items().map { it.event.id })
        assertEquals(true, store.bytes!!.toString(Charsets.UTF_8).contains("\"v\":2"))
    }

    @Test fun messagesAreKeptInOrderWithTheirTextAndSurviveReopen() = runBlocking {
        val store = MemoryStore()
        val a = chat(1, 1_000); val b = chat(2, 1_001); val c = chat(3, 1_002)
        val outbox = PendingChatOutbox(store, "room", "account", a.pubkey)
        outbox.retain("room-epoch", a, text = "first")
        outbox.retain("room-epoch", b, editable = false, text = "second")
        outbox.retain("room-epoch", c, text = "third")
        val reopened = PendingChatOutbox(store, "room", "account", a.pubkey).items()
        assertEquals(listOf(a.id, b.id, c.id), reopened.map { it.event.id })
        assertEquals(listOf("first", "second", "third"), reopened.map { it.text })
        assertEquals(listOf(true, false, true), reopened.map { it.editable })
        assertEquals(a, outbox.pending()?.event)
    }

    @Test fun aRoomKeepsFiftyAndNoMore() = runBlocking {
        val outbox = PendingChatOutbox(MemoryStore(), "room", "account", chat(0).pubkey)
        for (i in 1..PendingChatOutbox.MAX_ITEMS) outbox.retain("room-epoch", chat(i, 1_000L + i))
        var refused = false
        try { outbox.retain("room-epoch", chat(99, 2_000)) } catch (_: IllegalStateException) { refused = true }
        assertEquals(true, refused)
        assertEquals(PendingChatOutbox.MAX_ITEMS, outbox.items().size)
    }

    @Test fun confirmationRemovesOnlyTheMatchingEvent() = runBlocking {
        val outbox = PendingChatOutbox(MemoryStore(), "room", "account", chat(0).pubkey)
        val a = chat(1); val b = chat(2)
        outbox.retain("room-epoch", a); outbox.retain("room-epoch", b)
        outbox.confirm(a.id)
        assertEquals(listOf(b.id), outbox.items().map { it.event.id })
        outbox.confirm("another-event")
        assertEquals(1, outbox.items().size)
    }

    @Test fun takeAndEditOnlyWorkWhereNothingHasLeftThePhone() = runBlocking {
        val outbox = PendingChatOutbox(MemoryStore(), "room", "account", chat(0).pubkey)
        val a = chat(1); val b = chat(2); val c = chat(3); val d = chat(4); val e = chat(5)
        outbox.retain("room-epoch", a, text = "a"); outbox.retain("room-epoch", b, text = "b")
        outbox.retain("room-epoch", c, text = "c"); outbox.retain("room-epoch", d, editable = false, text = "d")
        outbox.retain("room-epoch", e, text = "e")
        outbox.setState(b.id, PendingChatState.REFUSED)
        outbox.setState(c.id, PendingChatState.MOVED)
        outbox.begin(e.id) // offered: written down as unknown
        assertEquals("a", outbox.take(a.id)?.text)
        assertEquals("b", outbox.take(b.id)?.text)
        assertEquals("c", outbox.take(c.id)?.text)
        assertNull(outbox.take(e.id))
        // A reaction is not put back in the composer, but may be deleted.
        assertNull(outbox.take(d.id, editableOnly = true))
        assertNotNull(outbox.take(d.id))
        assertEquals(listOf(e.id), outbox.items().map { it.event.id })
        assertEquals(PendingChatState.UNKNOWN, outbox.items().single().state)
    }

    @Test fun aMessageThatMayHaveGoneNeverReadsAsUnsent() = runBlocking {
        val outbox = PendingChatOutbox(MemoryStore(), "room", "account", chat(0).pubkey)
        val a = chat(1)
        outbox.retain("room-epoch", a)
        outbox.begin(a.id)
        assertEquals(false, outbox.setState(a.id, PendingChatState.REFUSED))
        assertEquals(false, outbox.setState(a.id, PendingChatState.MOVED))
        assertEquals(PendingChatState.UNKNOWN, outbox.items().single().state)
        assertNotNull(outbox.remove(a.id))
        assertNull(outbox.pending())
    }

    @Test fun ownerMismatchNeverLoadsAnotherAccountsPendingMessage() = runBlocking {
        val store = MemoryStore()
        val event = Events.sign(ByteArray(32) { 2 }, KIND_CHAT, 1_000,
            listOf(listOf("d", "room-epoch")), "ciphertext", ByteArray(32))
        PendingChatOutbox(store, "room", "account-a", event.pubkey).retain("room-epoch", event)
        var refused = false
        try { PendingChatOutbox(store, "room", "account-b", event.pubkey).pending() }
        catch (_: IllegalArgumentException) { refused = true }
        assertEquals(true, refused)
        assertNotNull(PendingChatOutbox(store, "room", "account-a", event.pubkey).pending())
    }

    @Test fun clearFromReopenedInstanceWaitsForOldWrite() = runBlocking {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = object : RoomStorage {
            var bytes: ByteArray? = null
            override fun read(): ByteArray? = bytes?.clone()
            override fun write(value: ByteArray) {
                writing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                bytes = value.clone()
            }
            override fun reset() { bytes = null }
        }
        val event = Events.sign(ByteArray(32) { 3 }, KIND_CHAT, 1_000,
            listOf(listOf("d", "room-epoch")), "ciphertext", ByteArray(32))
        val old = PendingChatOutbox(store, "room", "account", event.pubkey)
        val reopened = PendingChatOutbox(store, "room", "account", event.pubkey)
        val retain = async(Dispatchers.IO) { old.retain("room-epoch", event) }
        try {
            assertEquals(true, writing.await(5, TimeUnit.SECONDS))
            val clear = async(Dispatchers.IO) { reopened.clear() }
            delay(50)
            assertFalse(clear.isCompleted)
            release.countDown()
            retain.await(); clear.await()
            assertNull(reopened.pending())
        } finally { release.countDown() }
    }
}
