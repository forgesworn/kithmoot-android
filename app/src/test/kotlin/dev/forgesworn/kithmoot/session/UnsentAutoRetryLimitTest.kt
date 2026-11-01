package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.service.FlushOutcome
import dev.forgesworn.kithmoot.service.flushPending
import dev.forgesworn.kithmoot.storage.RING_CREDENTIAL_TTL
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A kept message is offered automatically for a day from its own created_at; after that only Retry sends it, signed afresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsentAutoRetryLimitTest {
    private class Store : RoomStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    private val day = UNSENT_AUTO_RETRY_LIMIT_SECONDS
    private val written = 1_000L

    /** Records every chat event the session actually offered, and says OK late to [acked]. */
    private class Offers(relay: FakeRelay) {
        val offered = mutableListOf<NostrEvent>()
        val acked = mutableSetOf<String>()
        private val inner = relay.transport()
        val transport: RoomTransport = object : RoomTransport by inner {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (event.kind == KIND_CHAT && stillAllowed()) offered += event
                return inner.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
            override fun relayAccepted(eventId: String): Boolean = eventId in acked
        }
    }

    private suspend fun TestScope.open(room: Room, owner: RoomIdentity, offers: Offers, store: Store,
        clock: () -> Long): Pair<RoomSession, PendingChatOutbox> {
        val outbox = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey)
        val live = RoomSession(room, owner, offers.transport, backgroundScope, timing = Fixtures.QUIET,
            now = clock, chatOutbox = outbox)
        live.join(); advanceTimeBy(1_000); runCurrent()
        return live to outbox
    }

    @Test fun `automatic offers stop a day after the message was written, and later messages still go`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val offers = Offers(relay); var clock = written
        val (live, outbox) = open(room, owner, offers, Store()) { clock }
        relay.confirmsPublications = false
        assertFalse(live.sendChatDurable("Held a while"))
        val original = outbox.items().single()
        assertEquals(PendingChatState.REFUSED, original.state)
        assertEquals(written + day, live.pendingChats.value.single().autoUntil)

        clock = written + day - 1
        assertFalse(live.retryPendingChat())
        assertEquals(2, offers.offered.size, "Still offered the second before a day")

        clock = written + day
        relay.confirmsPublications = true
        assertFalse(live.retryPendingChat())
        assertEquals(2, offers.offered.size, "Never offered again at a day")
        assertEquals(original.event, outbox.items().single().event)
        assertEquals(PendingChatState.REFUSED, outbox.items().single().state, "Left as it was, not moved")

        // Skipped, not a block: a new message behind it goes.
        assertTrue(live.sendChatDurable("Written later"))
        assertEquals(listOf(original.event), outbox.items().map { it.event })
        assertEquals(1, relay.countOfKind(KIND_CHAT))
        assertTrue(offers.offered.drop(2).none { it.id == original.event.id })
        live.leave()
    }

    @Test fun `a room opened on a message past a day leaves it unsent, as does the background flush`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val offers = Offers(relay); val store = Store(); var clock = written
        val (first, _) = open(room, owner, offers, store) { clock }
        relay.confirmsPublications = false
        assertFalse(first.sendChatDurable("Kept overnight"))
        first.leave()

        relay.confirmsPublications = true
        clock = written + day + 60
        val (second, outbox) = open(room, owner, offers, store) { clock }
        val kept = outbox.items().single()
        // What the room does on opening: read the journal, reconcile, and offer what waits.
        second.refreshPendingChats(); second.reconcilePendingChats()
        assertFalse(second.retryPendingChat())
        assertEquals(1, offers.offered.size, "Only the first attempt, a day ago")
        assertEquals(0, relay.countOfKind(KIND_CHAT))
        assertEquals(kept, outbox.items().single())
        assertEquals(PendingChatState.REFUSED, second.pendingChats.value.single().state)

        assertEquals(FlushOutcome.NOTHING, flushPending(outbox, room.roomId, offers.transport, now = clock))
        assertEquals(0, relay.countOfKind(KIND_CHAT))
        assertEquals(kept, outbox.items().single())
        // Before a day the background flush still sends it.
        assertEquals(FlushOutcome.SENT, flushPending(outbox, room.roomId, offers.transport, now = written + day - 1))
        assertEquals(listOf(kept.event.id), relay.published.filter { it.kind == KIND_CHAT }.map { it.id })
        second.leave()
    }

    @Test fun `Retry past a day signs it afresh, keeps its id, and the old event is never offered again`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val offers = Offers(relay); val store = Store(); var clock = written
        val (live, outbox) = open(room, owner, offers, store) { clock }
        relay.confirmsPublications = false
        assertFalse(live.sendChatDurable("Say it again"))
        val old = outbox.items().single()

        // Pressed while no relay is up: signed afresh at once and kept in the old one's place.
        clock = written + day + 5
        relay.confirmsPublications = true; relay.reachable = false
        assertFalse(live.retryPendingChat(pressed = true))
        val fresh = outbox.items().single()
        assertNotEquals(old.event.id, fresh.event.id)
        assertEquals(clock, fresh.event.createdAt)
        assertEquals(old.messageId, fresh.messageId)
        assertEquals(old.text, fresh.text)
        assertEquals(PendingChatState.WAITING, fresh.state)
        assertEquals(listOf(fresh), PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey).items())
        // A fresh window: a day, or the end of the credential it now carries if sooner.
        assertEquals(minOf(clock + day, Fixtures.CREDENTIAL_EXPIRY), live.pendingChats.value.single().autoUntil)

        // The new event goes on the next automatic attempt; the old one never does.
        relay.reachable = true
        clock += 30
        assertTrue(live.retryPendingChat())
        assertEquals(listOf(fresh.event.id), relay.published.filter { it.kind == KIND_CHAT }.map { it.id })
        val shown = live.chat.value.single()
        assertEquals(old.messageId, shown.id, "One message in the chat, under its own id")
        assertEquals("Say it again", shown.body)
        assertTrue(outbox.items().isEmpty())
        assertFalse(live.retryPendingChat(pressed = true))
        assertEquals(1, offers.offered.count { it.id == old.event.id }, "Only its first attempt, a day ago")
        live.leave()
    }

    @Test fun `Retry past a day sends a fresh event straight away when a relay is up`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val offers = Offers(relay); var clock = written
        val (live, outbox) = open(room, owner, offers, Store()) { clock }
        relay.confirmsPublications = false
        assertFalse(live.sendChatDurable("Straight away"))
        val old = outbox.items().single()
        clock = written + day + 600
        relay.confirmsPublications = true
        assertTrue(live.retryPendingChat(pressed = true))
        val sent = relay.published.single { it.kind == KIND_CHAT }
        assertNotEquals(old.event.id, sent.id)
        assertEquals(clock, sent.createdAt)
        assertEquals(old.messageId, live.chat.value.single().id)
        assertTrue(outbox.items().isEmpty())
        live.leave()
    }

    @Test fun `a message a relay already said OK to is cleared as sent, never signed again`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val offers = Offers(relay); var clock = written
        val (live, outbox) = open(room, owner, offers, Store()) { clock }
        relay.timesOut = true
        assertFalse(live.sendChatDurable("Answered late"))
        val old = outbox.items().single()
        assertEquals(PendingChatState.UNKNOWN, old.state)
        // The relay's OK came after the wait ran out.
        offers.acked += old.event.id
        relay.timesOut = false
        clock = written + day + 1
        live.retryPendingChat(pressed = true)
        assertEquals(0, relay.countOfKind(KIND_CHAT), "Nothing signed again or offered")
        assertEquals(1, offers.offered.size)
        assertTrue(outbox.items().isEmpty())
        assertEquals(old.messageId, live.chat.value.single().id)
        live.leave()
    }

    @Test fun `a Ring me room no longer offers the original after a day, though its credential lasts a week`() = runTest {
        val room = Fixtures.room()
        val owner = PrimaryIdentity.create(room.roomId, written + RING_CREDENTIAL_TTL, written, Fixtures.key(1), Fixtures.key(2))
        val relay = FakeRelay(); val offers = Offers(relay); var clock = written
        val (live, outbox) = open(room, owner, offers, Store()) { clock }
        relay.confirmsPublications = false
        assertFalse(live.sendChatDurable("Ring me"))
        val old = outbox.items().single()
        assertEquals(written + RING_CREDENTIAL_TTL,
            originalChatPublicationDeadline(old.event, room.roomId, room.roomKey), "Its own authority runs a week")

        relay.confirmsPublications = true
        for (at in listOf(written + day, written + 3 * day, written + RING_CREDENTIAL_TTL - 600)) {
            clock = at
            assertFalse(live.retryPendingChat())
            assertEquals(1, offers.offered.size, "Not offered at $at")
            assertEquals(old, outbox.items().single())
        }
        assertTrue(live.retryPendingChat(pressed = true))
        val sent = relay.published.single { it.kind == KIND_CHAT }
        assertNotEquals(old.event.id, sent.id)
        assertEquals(clock, sent.createdAt)
        live.leave()
    }

    @Test fun `once the credential inside it ends, before a day, it waits for Retry instead of being moved`() = runTest {
        val room = Fixtures.room()
        // An ordinary room's credential lasts a day and is renewed at half-life, so the one a message carries
        // can end twelve hours after it was written.
        val halfDay = day / 2
        fun identity(expiry: Long) = PrimaryIdentity.create(room.roomId, expiry, written, Fixtures.key(1), Fixtures.key(2))
        val relay = FakeRelay(); val offers = Offers(relay); val store = Store(); var clock = written
        val (first, _) = open(room, identity(written + halfDay), offers, store) { clock }
        relay.confirmsPublications = false
        assertFalse(first.sendChatDurable("Written late in a credential"))
        first.leave()

        relay.confirmsPublications = true
        clock = written + halfDay + 1
        val (second, outbox) = open(room, identity(written + 2 * day), offers, store) { clock }
        val old = outbox.items().single()
        second.refreshPendingChats()
        assertEquals(written + halfDay, second.pendingChats.value.single().autoUntil)
        assertFalse(second.retryPendingChat())
        assertEquals(1, offers.offered.size)
        assertEquals(old, outbox.items().single(), "Not moved, not offered")

        assertTrue(second.retryPendingChat(pressed = true))
        val sent = relay.published.single { it.kind == KIND_CHAT }
        assertNotEquals(old.event.id, sent.id)
        assertEquals(written + 2 * day, originalChatPublicationDeadline(sent, room.roomId, room.roomKey), "Under the renewed credential")
        assertEquals(old.messageId, second.chat.value.single().id)
        second.leave()
    }

    @Test fun `replace swaps one signed message for another in place, in one write`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val store = Store()
        val outbox = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey)
        fun event(at: Long) = Events.sign(owner.deviceSecretKey, KIND_CHAT, at, listOf(listOf("d", room.roomId)), "c$at", ByteArray(32))
        val a = event(1); val b = event(2); val c = event(3)
        outbox.retain(room.roomId, a, text = "a", messageId = "ma")
        outbox.retain(room.roomId, b, text = "b", messageId = "mb")
        outbox.setState(a.id, PendingChatState.REFUSED)
        val fresh = PendingChatOutbox.Pending(room.roomId, c, text = "a", messageId = "ma")
        assertTrue(outbox.replace(a.id, fresh))
        val reopened = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey).items()
        assertEquals(listOf(c.id, b.id), reopened.map { it.event.id })
        assertEquals(PendingChatState.WAITING, reopened[0].state)
        assertFalse(outbox.replace(a.id, fresh), "The old one is gone: nothing written")
        assertEquals(reopened, outbox.items())
    }
}
