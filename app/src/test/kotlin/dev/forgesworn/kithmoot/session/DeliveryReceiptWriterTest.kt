package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.deliveryReceiptIdHash
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web client's `delivery-receipt-writer.test.ts`, rule for rule: when this phone confirms what reached it. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryReceiptWriterTest {
    private class MemoryStorage : RoomStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(value: ByteArray) { bytes = value.copyOf() }
        override fun reset() { bytes = null }
        fun text() = bytes?.toString(Charsets.UTF_8).orEmpty()
    }

    private data class Call(val d: String, val ids: List<String>, val createdAt: Long, val index: Int)

    private class Publisher(var accept: Boolean = true) : DeliveryReceiptPublisher {
        val calls = mutableListOf<Call>()
        var hold: CompletableDeferred<Unit>? = null
        var timesOut = false
        override suspend fun publish(d: String, messageIds: List<String>, createdAt: Long, index: Int): Boolean {
            calls += Call(d, messageIds, createdAt, index)
            hold?.await()
            if (timesOut) withTimeout(1) { awaitCancellation() }
            return accept
        }
    }

    private val me = "aa".repeat(32)
    private val them = "bb".repeat(32)
    private val stream = "dd".repeat(32)
    private val start = 1_800_000_000_000L

    private fun TestScope.nowS() = (start + testScheduler.currentTime) / 1000
    private fun message(id: String, sentAt: Long, from: String = them, reaction: ChatReaction? = null, replaces: String? = null) =
        ChatMessage(id = id, participant = from, device = "cc".repeat(32), body = "hello", sentAt = sentAt, reaction = reaction, replaces = replaces)

    private fun TestScope.writer(storage: RoomStorage? = MemoryStorage(), enabled: () -> Boolean = { true }) =
        DeliveryReceiptWriter(storage, backgroundScope, EmptyCoroutineContext, now = { start + testScheduler.currentTime },
            random = Random(11), enabled = enabled)

    @Test fun `the first receipt waits 2 to 6 seconds and carries every waiting id`() = runTest {
        val publisher = Publisher()
        val writer = writer()
        writer.received(message("m1", nowS()), stream, me, publisher)
        writer.received(message("m2", nowS()), stream, me, publisher)
        writer.received(message("m2", nowS()), stream, me, publisher)
        advanceTimeBy(1_999); runCurrent()
        assertTrue(publisher.calls.isEmpty())
        assertEquals(2, writer.waiting())
        advanceTimeBy(4_002); runCurrent()
        assertEquals(1, publisher.calls.size)
        assertEquals(listOf("m1", "m2"), publisher.calls.single().ids)
        assertEquals(stream, publisher.calls.single().d)
        assertEquals(0, publisher.calls.single().index)
        assertEquals(0, writer.waiting())
    }

    @Test fun `the next receipt for a stream waits at least 30 seconds after the last`() = runTest {
        val publisher = Publisher()
        val writer = writer()
        writer.received(message("m1", nowS()), stream, me, publisher)
        advanceTimeBy(6_001); runCurrent()
        val first = publisher.calls.single().createdAt
        writer.received(message("m2", nowS()), stream, me, publisher)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, publisher.calls.size)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(2, publisher.calls.size)
        assertTrue(publisher.calls[1].createdAt - first >= 30)
        // Another stream has its own clock.
        writer.received(message("m3", nowS()), "ee".repeat(32), me, publisher)
        advanceTimeBy(6_001); runCurrent()
        assertEquals(3, publisher.calls.size)
    }

    @Test fun `over 256 waiting ids split into receipts of one second, indexed`() = runTest {
        val publisher = Publisher()
        val writer = writer()
        repeat(300) { writer.received(message("m$it", nowS()), stream, me, publisher) }
        advanceTimeBy(6_001); runCurrent()
        assertEquals(listOf(256, 44), publisher.calls.map { it.ids.size })
        assertEquals(listOf(0, 1), publisher.calls.map { it.index })
        assertEquals(1, publisher.calls.map { it.createdAt }.distinct().size)
    }

    @Test fun `own messages, rows that are not messages, history from before this device and anything over a week are never confirmed`() = runTest {
        val publisher = Publisher()
        val writer = writer()
        val now = nowS()
        writer.received(message("mine", now, from = me), stream, me, publisher)
        writer.received(message("mine-upper", now, from = me.uppercase()), stream, me, publisher)
        writer.received(message("reaction", now, reaction = ChatReaction("x", them, "👍", true, 1)), stream, me, publisher)
        writer.received(message("edit", now, replaces = "x"), stream, me, publisher)
        writer.received(message("before", now - 61), stream, me, publisher)
        assertEquals(0, writer.waiting())
        // A minute's slack for clocks.
        writer.received(message("slack", now - 60), stream, me, publisher)
        assertEquals(1, writer.waiting())
    }

    @Test fun `first seen is the writer's own, kept across visits, never a later opening`() = runTest {
        val storage = MemoryStorage()
        val firstSeen = nowS()
        writer(storage).preload()
        assertTrue(storage.text().contains("\"s\":$firstSeen"))
        advanceTimeBy(3_600_000)
        // An hour later, the room opened again: a message from just after it was first seen still counts.
        val publisher = Publisher()
        val later = writer(storage)
        later.received(message("backlog", firstSeen + 10), stream, me, publisher)
        assertEquals(1, later.waiting())
        later.received(message("too-old", firstSeen - 61), stream, me, publisher)
        assertEquals(1, later.waiting())
        // Nothing older than a week, whatever the first-seen time says.
        advanceTimeBy(8L * 24 * 3600 * 1000)
        val week = writer(storage)
        week.received(message("stale", firstSeen + 20), stream, me, publisher)
        assertEquals(0, week.waiting())
    }

    @Test fun `an id counts as confirmed only after a relay took the receipt, and is not confirmed twice`() = runTest {
        val storage = MemoryStorage()
        val publisher = Publisher(accept = false)
        val writer = writer(storage)
        val msg = message("m1", nowS())
        writer.received(msg, stream, me, publisher)
        advanceTimeBy(6_001); runCurrent()
        assertEquals(1, publisher.calls.size)
        assertEquals(1, writer.waiting())
        assertFalse(storage.text().contains(deliveryReceiptIdHash("m1")))
        publisher.accept = true
        advanceTimeBy(30_001); runCurrent()
        assertEquals(2, publisher.calls.size)
        assertEquals(0, writer.waiting())
        assertTrue(storage.text().contains(deliveryReceiptIdHash("m1")))
        writer.received(msg, stream, me, publisher)
        assertEquals(0, writer.waiting())
        // A later visit remembers it too.
        val again = writer(storage)
        again.received(msg, stream, me, publisher)
        assertEquals(0, again.waiting())
    }

    @Test fun `a relay that never answers leaves the ids waiting, not lost`() = runTest {
        val publisher = Publisher().apply { timesOut = true }
        val writer = writer()
        writer.received(message("m1", nowS()), stream, me, publisher)
        advanceTimeBy(6_001); runCurrent()
        assertEquals(1, publisher.calls.size)
        assertEquals(1, writer.waiting())
        publisher.timesOut = false
        advanceTimeBy(30_001); runCurrent()
        assertEquals(2, publisher.calls.size)
        assertEquals(0, writer.waiting())
    }

    @Test fun `turned off, nothing is sent and nothing waits`() = runTest {
        val publisher = Publisher()
        var on = false
        val writer = writer(enabled = { on })
        writer.received(message("m1", nowS()), stream, me, publisher)
        advanceTimeBy(6_001); runCurrent()
        assertTrue(publisher.calls.isEmpty())
        assertEquals(0, writer.waiting())
        on = true
        writer.received(message("m2", nowS()), stream, me, publisher)
        advanceTimeBy(6_001); runCurrent()
        assertEquals(listOf("m2"), publisher.calls.single().ids)
    }

    @Test fun `a room closed while its receipt is being signed records nothing and sends no more`() = runTest {
        val storage = MemoryStorage()
        val publisher = Publisher().apply { hold = CompletableDeferred() }
        val writer = writer(storage)
        writer.received(message("m1", nowS()), stream, me, publisher)
        advanceTimeBy(6_001); runCurrent()
        assertEquals(1, publisher.calls.size)
        val before = storage.text()
        writer.cancel()
        publisher.hold!!.complete(Unit)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(before, storage.text())
        assertEquals(1, publisher.calls.size)
        assertEquals(0, writer.waiting())
    }

    @Test fun `forgetting the room forgets what it confirmed`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage)
        writer.received(message("m1", nowS()), stream, me, Publisher())
        advanceTimeBy(6_001); runCurrent()
        assertTrue(storage.bytes != null)
        writer.forget()
        assertEquals(null, storage.bytes)
        writer.received(message("m2", nowS()), stream, me, Publisher())
        assertEquals(0, writer.waiting())
    }

    @Test fun `unreadable storage starts again rather than throwing`() = runTest {
        val storage = MemoryStorage().apply { bytes = "not json".toByteArray() }
        val writer = writer(storage)
        writer.received(message("m1", nowS()), stream, me, Publisher())
        assertEquals(1, writer.waiting())
    }
}
