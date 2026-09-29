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
