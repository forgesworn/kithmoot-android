package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KIND_DELIVERY_RECEIPT
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Delivery receipts through a room: a reader's device confirms, and the sender's message shows who. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryReceiptSessionTest {
    private class MemoryStorage : RoomStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(value: ByteArray) { bytes = value.copyOf() }
        override fun reset() { bytes = null }
    }

    private val room = Fixtures.room()
    private val alice = Fixtures.primary(room, 1, 2)
    private val bob = Fixtures.primary(room, 40, 41)

    private fun TestScope.writer(storage: RoomStorage? = null) =
        DeliveryReceiptWriter(storage, backgroundScope, EmptyCoroutineContext, now = { testScheduler.currentTime }, random = Random(3))
    private fun TestScope.ledger(storage: RoomStorage? = null) = DeliveredLedger(storage, now = { testScheduler.currentTime })

    @Test fun `a message another person's device reads shows two ticks with who on the sender's`() = runTest {
        val relay = FakeRelay()
        val a = session(room, alice, relay, receiptWriter = writer(), deliveredLedger = ledger())
        val b = session(room, bob, relay, receiptWriter = writer(), deliveredLedger = ledger())
        a.join(); b.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(a.sendChatConfirmed("Who has the keys?"))
        runCurrent()
        val sent = a.chat.value.single()
        assertEquals(sent.id, b.chat.value.single().id)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(0, relay.countOfKind(KIND_DELIVERY_RECEIPT))
        advanceTimeBy(5_000); runCurrent()
        // One receipt, from Bob's device, signed by a key made for it alone.
        val receipt = relay.published.single { it.kind == KIND_DELIVERY_RECEIPT }
        assertTrue(receipt.pubkey != bob.devicePubkey && receipt.pubkey != bob.participant)
        assertEquals(listOf(bob.participant), a.delivered.value[sent.id]?.map { it.participant })
        // Alice's own device never confirms her message, and Bob has nothing of his own confirmed.
        assertTrue(b.delivered.value.isEmpty())
        a.leave(); b.leave()
    }

    @Test fun `somebody blocked on this phone never lights the ticks`() = runTest {
        val relay = FakeRelay()
        val a = session(room, alice, relay, deliveredLedger = ledger(), blockedParticipant = { it == bob.participant })
        val b = session(room, bob, relay, receiptWriter = writer())
        a.join(); b.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(a.sendChatConfirmed("hello"))
        advanceTimeBy(7_000); runCurrent()
        assertEquals(1, relay.countOfKind(KIND_DELIVERY_RECEIPT))
        assertTrue(a.delivered.value.isEmpty())
        a.leave(); b.leave()
    }

    @Test fun `a device that leaves before its receipt goes sends none, and a new device confirms no history`() = runTest {
        val relay = FakeRelay().apply { replays = true }
        val a = session(room, alice, relay)
        val b = session(room, bob, relay, receiptWriter = writer())
        a.join(); b.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(a.sendChatConfirmed("hello"))
        runCurrent()
        b.leave()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, relay.countOfKind(KIND_DELIVERY_RECEIPT))
        // Two minutes on, Bob's new device opens the room and reads the message from history.
        advanceTimeBy(60_000)
        val newDevice = session(room, Fixtures.secondary(room, bob, 42), relay, receiptWriter = writer())
        newDevice.join(); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, newDevice.chat.value.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, relay.countOfKind(KIND_DELIVERY_RECEIPT))
        a.leave(); newDevice.leave()
    }

    @Test fun `a sender away when the receipt went sees two ticks on return`() = runTest {
        val relay = FakeRelay().apply { replays = true }
        val kept = MemoryStorage()
        val a = session(room, alice, relay, deliveredLedger = ledger(kept))
        a.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(a.sendChatConfirmed("hello"))
        val sent = a.chat.value.single()
        a.leave()
        val b = session(room, bob, relay, receiptWriter = writer())
        b.join(); advanceTimeBy(8_000); runCurrent()
        assertEquals(1, relay.countOfKind(KIND_DELIVERY_RECEIPT))
        b.leave()
        val back = session(room, alice, relay, deliveredLedger = ledger(kept))
        back.join(); advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(bob.participant), back.delivered.value[sent.id]?.map { it.participant })
        back.leave()
    }

    @Test fun `a quiet room neither asks for receipts nor sends one`() = runTest {
        val relay = FakeRelay()
        val filters = mutableListOf<Filter>()
        val inner = relay.transport()
        val watching = object : RoomTransport by inner {
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> { filtersSeen(filters); return inner.subscribe(filters) }
            fun filtersSeen(list: List<Filter>) { filters += list }
        }
        val quiet = RoomSession(room, alice, watching, backgroundScope, timing = Fixtures.QUIET, now = { testScheduler.currentTime / 1000 },
            deliveryInDrops = true, receiptWriter = writer(), deliveredLedger = ledger())
        quiet.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(filters.isNotEmpty())
        assertTrue(filters.none { KIND_DELIVERY_RECEIPT in it.kinds.orEmpty() })
        assertFalse(quiet.publishDeliveryReceipt(room.roomId, listOf("m1"), 1, 0))
        quiet.leave()

        // An ordinary room asks, under a filter of its own with its own limit.
        filters.clear()
        val open = RoomSession(room, alice, watching, backgroundScope, timing = Fixtures.QUIET, now = { testScheduler.currentTime / 1000 },
            deliveredLedger = ledger())
        open.join(); advanceTimeBy(1_000); runCurrent()
        val receipts = filters.single { KIND_DELIVERY_RECEIPT in it.kinds.orEmpty() }
        assertEquals(listOf(KIND_DELIVERY_RECEIPT), receipts.kinds)
        assertEquals(MAX_DELIVERY_RECEIPTS_FETCHED, receipts.limit)
        assertEquals(listOf(room.roomId), receipts.tags["#d"])
        assertNull(filters.single { KIND_CHAT in it.kinds.orEmpty() }.limit)
        open.leave()
    }
}
