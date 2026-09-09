package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two people in a quiet room over one relay: what the relay holds is gift
 * wraps and nothing of the chat; what the other person reads is the chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuietTransportTest {

    private val room = Fixtures.room()
    private val ada = Fixtures.primary(room, 1, 2)
    private val rowan = Fixtures.primary(room, 3, 4)
    private val policy = RoomPolicy(KindredTier.OPEN, null, listOf(ada.participant, rowan.participant), quiet = true)
    private var clock = 498216L * 3600 + 10

    private fun TestScope.quiet(relay: FakeRelay, who: PrimaryIdentity, slot: Int = 0, restore: QuietTransport.QuietState? = null, onState: (QuietTransport.QuietState) -> Unit = {}) =
        QuietTransport(relay.transport(), room.roomKey, who.participant, policy.members!!, slot, backgroundScope, intervalSeconds = 60, now = { clock }, restore = restore, onState = onState, ticking = false, slotOffset = { 0 })

    private fun chat(from: PrimaryIdentity, text: String) = encodeChatEvent(
        body = text, participant = from.participant, credential = from.credential, roomId = room.roomId,
        roomKey = room.roomKey, deviceSecretKey = from.deviceSecretKey, sentAt = clock,
    )

    @Test
    fun `chat crosses as gift wraps and the other side reads it`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        val b = quiet(relay, rowan)
        val seen = mutableListOf<NostrEvent>()
        val collector = launch { b.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(room.roomId))))).collect { seen += it } }
        runCurrent()

        val message = chat(ada, "meet at the mill")
        a.publish(message)
        assertEquals(1, a.pending)
        // Nothing leaves before the slot's moment; then one wrap, and the chat is read.
        a.tick(); runCurrent()
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(0, relay.countOfKind(KIND_CHAT))
        assertEquals(0, a.pending)
        assertEquals(listOf(message.id), seen.map { it.id })
        val wrap = relay.published.single { it.kind == RoomDrops.GIFT_WRAP_KIND }
        assertEquals(listOf("p"), wrap.tags.map { it[0] })
        assertTrue(wrap.pubkey != ada.devicePubkey && wrap.pubkey != ada.participant)

        // An empty slot posts a filler the same size; the reader shows nothing new.
        clock += 60
        a.tick(); runCurrent()
        val wraps = relay.published.filter { it.kind == RoomDrops.GIFT_WRAP_KIND }
        assertEquals(2, wraps.size)
        assertEquals(wraps[0].content.length, wraps[1].content.length)
        assertEquals(1, seen.size)

        // The relay replaying the wrap costs nothing and shows nothing twice.
        relay.publish(wrap); runCurrent()
        assertEquals(1, seen.size)
        collector.cancel(); a.stop(); b.stop()
    }

    @Test
    fun `plain kinds pass straight through, mixed filters are split`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        val roster = NostrEvent(20461, clock, listOf(listOf("d", room.roomId)), "x", "0".repeat(64), "1".repeat(64), "2".repeat(128))
        val seen = mutableListOf<Int>()
        val collector = launch { a.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT, 20461), tags = mapOf("#d" to listOf(room.roomId))))).collect { seen += it.kind } }
        runCurrent()
        a.publish(roster); runCurrent()
        assertEquals(listOf(20461), seen)
        assertEquals(1, relay.countOfKind(20461))
        collector.cancel(); a.stop()
    }

    @Test
    fun `a non-member and a third device read but cannot post`() = runTest {
        val relay = FakeRelay()
        val stranger = Fixtures.primary(room, 70, 71)
        val s = quiet(relay, stranger)
        assertFalse(s.canSend)
        assertFailsWith<IllegalStateException> { s.publish(chat(stranger, "hi")) }
        val third = quiet(relay, ada, slot = 2)
        assertFalse(third.canSend)
        assertFailsWith<IllegalStateException> { third.publish(chat(ada, "hi")) }
        assertTrue(quiet(relay, ada, slot = 1).canSend)
        s.stop(); third.stop()
    }

    @Test
    fun `counters are kept between visits and a waiting message survives a restart`() = runTest {
        val relay = FakeRelay()
        val states = mutableListOf<QuietTransport.QuietState>()
        val a = quiet(relay, ada, onState = { states += it })
        val m1 = chat(ada, "one")
        val m2 = chat(ada, "two")
        a.publish(m1); a.publish(m2)
        a.tick(); runCurrent()
        assertEquals(1, a.pending)
        val kept = states.last()
        assertEquals(listOf(m2.id), kept.queued.map { it.id })
        assertEquals(1, kept.used.getValue(ada.participant).counters.size)
        // A fresh transport from the kept state: one message waiting, one counter spent.
        val again = quiet(relay, ada, restore = kept)
        assertEquals(1, again.pending)
        assertEquals(kept.used.getValue(ada.participant).counters, again.exportState().used.getValue(ada.participant).counters)
        clock += 60
        again.tick(); runCurrent()
        assertEquals(0, again.pending)
        assertEquals(2, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(2, again.exportState().used.getValue(ada.participant).counters.size)
        a.stop(); again.stop()
    }

    @Test
    fun `eight messages an hour from one device, the ninth waits for the next epoch`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        repeat(9) { a.publish(chat(ada, "m$it")) }
        repeat(9) { a.tick(); runCurrent(); clock += 60 }
        assertEquals(1, a.pending)
        assertEquals(9, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND)) // eight drops and one filler
        clock += 3600
        a.tick(); runCurrent()
        assertEquals(0, a.pending)
        a.stop()
    }

    @Test
    fun `a message too long for the bucket is refused to the caller`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay, ada)
        assertFailsWith<IllegalArgumentException> { a.publish(chat(ada, "x".repeat(2000)).let { it.copy(content = it.content + "y".repeat(6000)) }) }
        assertEquals(0, a.pending)
        a.stop()
    }
}
