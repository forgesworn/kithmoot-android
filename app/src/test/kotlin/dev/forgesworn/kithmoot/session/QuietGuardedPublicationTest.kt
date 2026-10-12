package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
import dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuietGuardedPublicationTest {
    private val room = Fixtures.room()
    private val identity = Fixtures.primary(room, 1, 2)
    private var clock = 498216L * 3600 + 10

    private fun event(text: String) = encodeChatEvent(text, identity.participant,
        identity.credential, room.roomId, room.roomKey, identity.deviceSecretKey, clock)

    private fun TestScope.quiet(inner: RoomTransport,
        restore: QuietTransport.QuietState? = null,
        onState: (QuietTransport.QuietState) -> Unit = {}) = QuietTransport(inner,
        room.roomKey, identity.participant, listOf(identity.participant), 0, backgroundScope,
        intervalSeconds = 60, now = { clock }, ticking = false, slotOffset = { 0 },
        restore = restore, onState = onState)

    @Test fun `guarded receipt waits for delivery and never joins the restart or box queue`() = runTest {
        val relay = FakeRelay().apply { confirmsPublications = false }
        var writes = 0
        val a = quiet(relay.transport(), onState = { writes++ })
        val message = event("owned by the recording draft")
        val send = async { a.publishConfirmedGuarded(message, a.publicationGeneration(), { true }) }
        runCurrent()
        assertFalse(send.isCompleted)
        assertEquals(1, a.pending)
        assertTrue(a.exportState().queued.isEmpty())
        assertTrue(a.queuedEvents().isEmpty())
        assertFalse(a.confirmQueued(message.id))
        a.tick(); runCurrent()
        a.tick(); a.tick()
        assertEquals(1, writes)
        assertFalse(send.isCompleted)
        assertTrue(relay.published.isEmpty())
        relay.confirmsPublications = true
        a.tick(); runCurrent()
        assertTrue(send.await())
        assertEquals(0, a.pending)
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertEquals(0, relay.countOfKind(KIND_CHAT))
        // An acknowledgement lost by the caller can retry the same exact event.
        assertTrue(a.publishConfirmedGuarded(message, a.publicationGeneration(), { true }))
        assertEquals(0, a.pending)
        assertEquals(1, relay.published.size)
        assertEquals(2, writes)
        assertFailsWith<PublicationNotOfferedException> {
            a.publishConfirmedGuarded(message, a.publicationGeneration(), { false })
        }
        a.stop()
    }

    @Test fun `a denied guard cannot retain or publish anything`() = runTest {
        val relay = FakeRelay()
        var writes = 0
        val a = quiet(relay.transport(), onState = { writes++ })
        assertFailsWith<PublicationNotOfferedException> {
            a.publishConfirmedGuarded(event("denied"), a.publicationGeneration(), { false })
        }
        assertEquals(0, writes)
        assertEquals(0, a.pending)
        assertTrue(relay.published.isEmpty())
        a.stop()
    }

    @Test fun `ownership withdrawn before a slot rejects the request and only posts filler`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay.transport())
        var owned = true
        val send = async { runCatching { a.publishConfirmedGuarded(event("withdrawn"), a.publicationGeneration(), { owned }) } }
        runCurrent()
        owned = false
        a.tick(); runCurrent()
        assertIs<PublicationNotOfferedException>(send.await().exceptionOrNull())
        assertEquals(0, a.pending)
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        assertTrue(a.exportState().used.isEmpty())
        a.stop()
    }

    @Test fun `the underlying dispatch rechecks ownership after slot selection`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        var owned = true
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                owned = false
                return delegate.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
        }
        val a = quiet(inner)
        val send = async { runCatching { a.publishConfirmedGuarded(event("late withdrawal"), a.publicationGeneration(), { owned }, 100) } }
        runCurrent(); a.tick()
        assertTrue(relay.published.isEmpty())
        advanceTimeBy(101); runCurrent()
        // Conservatively ambiguous once a dispatch was attempted.
        assertIs<PublicationUnconfirmedException>(send.await().exceptionOrNull())
        a.tick()
        assertTrue(relay.published.isEmpty())
        assertEquals(0, a.pending)
        a.stop()
    }

    @Test fun `timeout before dispatch removes only the temporary request`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay.transport())
        val ordinary = event("ordinary durable chat")
        a.publish(ordinary)
        val send = async { runCatching { a.publishConfirmedGuarded(event("temporary"), a.publicationGeneration(), { true }, 100) } }
        runCurrent()
        assertEquals(listOf(ordinary.id), a.exportState().queued.map { it.id })
        advanceTimeBy(101); runCurrent()
        assertIs<PublicationNotOfferedException>(send.await().exceptionOrNull())
        assertEquals(listOf(ordinary.id), a.queuedEvents().map { it.id })
        a.tick()
        assertEquals(0, a.pending)
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        a.stop()
    }

    @Test fun `an unknown offer never sends a replacement wrap in the same slot after timeout`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (!stillAllowed()) throw PublicationNotOfferedException()
                relay.publish(event)
                throw PublicationUnconfirmedException()
            }
        }
        val a = quiet(inner)
        val send = async { runCatching { a.publishConfirmedGuarded(event("unknown receipt"), a.publicationGeneration(), { true }, 100) } }
        runCurrent(); a.tick()
        assertEquals(1, relay.published.size)
        advanceTimeBy(101); runCurrent()
        assertIs<PublicationUnconfirmedException>(send.await().exceptionOrNull())
        a.tick()
        assertEquals(1, relay.published.size)
        clock += 60
        a.tick()
        assertEquals(2, relay.published.size)
        assertEquals(2, relay.published.map { it.id }.distinct().size)
        a.stop()
    }

    @Test fun `rekey rejects waiting owners and invalidates captured generations`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay.transport())
        val previous = a.publicationGeneration()
        val send = async { runCatching { a.publishConfirmedGuarded(event("old room state"), previous, { true }) } }
        runCurrent()
        a.beginRekey(); runCurrent()
        assertIs<PublicationNotOfferedException>(send.await().exceptionOrNull())
        a.rekey(ByteArray(32) { 22 }); a.completeRekey()
        assertFailsWith<PublicationNotOfferedException> {
            a.publishConfirmedGuarded(event("stale generation"), previous, { true })
        }
        assertTrue(a.publicationGeneration() != previous)
        assertEquals(0, a.pending)
        assertTrue(relay.published.isEmpty())
        a.stop()
    }

    @Test fun `restart retains spent counters but cannot replay a guarded recording event`() = runTest {
        val relay = FakeRelay().apply { confirmsPublications = false }
        var saved: QuietTransport.QuietState? = null
        val a = quiet(relay.transport(), onState = { saved = it })
        val send = async { runCatching { a.publishConfirmedGuarded(event("must recheck after restart"), a.publicationGeneration(), { true }) } }
        runCurrent(); a.tick()
        assertEquals(1, saved!!.used.getValue(identity.participant).counters.size)
        assertTrue(saved!!.queued.isEmpty())
        a.stop(); runCurrent()
        assertIs<PublicationUnconfirmedException>(send.await().exceptionOrNull())
        val again = quiet(relay.transport(), restore = saved)
        assertEquals(0, again.pending)
        assertEquals(1, again.exportState().used.getValue(identity.participant).counters.size)
        relay.confirmsPublications = true
        again.tick()
        assertTrue(relay.published.isEmpty())
        clock += 60
        again.tick()
        assertEquals(1, relay.countOfKind(RoomDrops.GIFT_WRAP_KIND))
        again.stop()
    }

    @Test fun `cancellation before dispatch leaves no replayable request`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay.transport())
        val send = async { a.publishConfirmedGuarded(event("cancelled"), a.publicationGeneration(), { true }) }
        runCurrent(); send.cancel(); runCurrent()
        assertEquals(0, a.pending)
        assertTrue(a.exportState().queued.isEmpty())
        a.tick()
        assertTrue(a.exportState().used.isEmpty())
        a.stop()
    }

    @Test fun `failed counter persistence prevents an offer and leaves retry authority with the caller`() = runTest {
        val relay = FakeRelay()
        val a = quiet(relay.transport(), onState = { error("storage unavailable") })
        val send = async { runCatching { a.publishConfirmedGuarded(event("no saved counter"), a.publicationGeneration(), { true }, 100) } }
        runCurrent()
        assertFailsWith<IllegalStateException> { a.tick() }
        assertTrue(relay.published.isEmpty())
        advanceTimeBy(101); runCurrent()
        assertIs<PublicationNotOfferedException>(send.await().exceptionOrNull())
        assertEquals(0, a.pending)
        assertTrue(a.exportState().queued.isEmpty())
        a.stop()
    }

    @Test fun `restart after an unknown offer preserves the occupied slot`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        var saved: QuietTransport.QuietState? = null
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (!stillAllowed()) throw PublicationNotOfferedException()
                relay.publish(event)
                throw PublicationUnconfirmedException()
            }
        }
        val a = quiet(inner, onState = { saved = it })
        val send = async { runCatching { a.publishConfirmedGuarded(event("receipt lost before restart"), a.publicationGeneration(), { true }) } }
        runCurrent(); a.tick()
        assertEquals(1, relay.published.size)
        a.stop(); runCurrent()
        assertIs<PublicationUnconfirmedException>(send.await().exceptionOrNull())
        val again = quiet(relay.transport(), restore = saved)
        again.tick()
        assertEquals(1, relay.published.size)
        clock += 60
        again.tick()
        assertEquals(2, relay.published.size)
        assertEquals(0, again.pending)
        again.stop()
    }

    @Test fun `rekey after an unknown offer cannot occupy the same slot again`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        var unknown = true
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (unknown) {
                    if (!stillAllowed()) throw PublicationNotOfferedException()
                    relay.publish(event)
                    throw PublicationUnconfirmedException()
                }
                return delegate.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
        }
        val a = quiet(inner)
        val old = async { runCatching { a.publishConfirmedGuarded(event("unknown old state"), a.publicationGeneration(), { true }) } }
        runCurrent(); a.tick()
        a.beginRekey(); a.rekey(ByteArray(32) { 22 }); a.completeRekey(); runCurrent()
        assertIs<PublicationUnconfirmedException>(old.await().exceptionOrNull())
        unknown = false
        val fresh = async { a.publishConfirmedGuarded(event("new state"), a.publicationGeneration(), { true }) }
        runCurrent(); a.tick()
        assertFalse(fresh.isCompleted)
        assertEquals(1, relay.published.size)
        clock += 60
        a.tick(); runCurrent()
        assertTrue(fresh.await())
        assertEquals(2, relay.published.size)
        a.stop()
    }

    @Test fun `a late receipt completes the current retry of the same exact event`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        val receipt = CompletableDeferred<Boolean>()
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (!stillAllowed()) throw PublicationNotOfferedException()
                relay.publish(event)
                return receipt.await()
            }
        }
        val a = quiet(inner)
        val message = event("exact retry while receipt is in flight")
        val previous = async { runCatching { a.publishConfirmedGuarded(message, a.publicationGeneration(), { true }, 100) } }
        runCurrent()
        val slot = async { a.tick() }
        runCurrent()
        assertEquals(1, relay.published.size)
        advanceTimeBy(101); runCurrent()
        assertIs<PublicationUnconfirmedException>(previous.await().exceptionOrNull())
        val retry = async { a.publishConfirmedGuarded(message, a.publicationGeneration(), { true }) }
        runCurrent()
        assertEquals(1, a.pending)
        assertFalse(retry.isCompleted)
        receipt.complete(true); runCurrent()
        slot.await()
        assertTrue(retry.await())
        assertEquals(0, a.pending)
        assertEquals(1, relay.published.size)
        a.stop()
    }

    @Test fun `ordinary queue removal in flight cannot consume a new guarded owner`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        val receipt = CompletableDeferred<Boolean>()
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
                relay.publish(event)
                return receipt.await()
            }
        }
        val a = quiet(inner)
        val message = event("new owner after queue release")
        a.publish(message)
        val ordinarySlot = async { a.tick() }
        runCurrent()
        assertTrue(a.confirmQueued(message.id))
        val send = async { a.publishConfirmedGuarded(message, a.publicationGeneration(), { true }) }
        runCurrent()
        receipt.complete(true); runCurrent(); ordinarySlot.await()
        assertEquals(1, a.pending)
        assertFalse(send.isCompleted)
        clock += 60
        a.tick(); runCurrent()
        assertTrue(send.await())
        assertEquals(0, a.pending)
        assertEquals(2, relay.published.size)
        a.stop()
    }
}
