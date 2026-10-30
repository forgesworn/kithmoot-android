package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a pool answers a relay that asks it to slow down or to stop. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayPoolRefusalTest {
    private val one = "wss://one.example"
    private val two = "wss://two.example"

    private fun event(id: String, kind: Int) = NostrEvent(kind = kind, createdAt = 1799995000,
        tags = listOf(listOf("d", "room")), content = "opaque", pubkey = "aa".repeat(32), id = id, sig = "cc".repeat(64))

    /** Past any hold: the longest first hold plus its resume jitter. */
    private val pastFirstHold = RelayGate.FIRST_HOLD_MS + 5_001

    @Test fun `a rate limit holds writes to that relay, dropping heartbeats and keeping the rest for the lift`() = runTest {
        val sockets = FakeSocketFactory()
        val gate = RelayGate()
        val pool = RelayPool(listOf(one, two), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = gate)
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        val first = event("01".repeat(32), 1464)
        pool.publish(first); runCurrent()
        val limited = sockets.forUrl(one).single()
        limited.deliverRaw("""["OK","${first.id}",false,"ERROR: rate-limited: slow down"]"""); runCurrent()
        assertTrue(gate.isHeld(one, currentTime))

        val heartbeat = event("02".repeat(32), 20461)
        val bell = event("03".repeat(32), 1464)
        pool.publish(heartbeat); pool.publish(bell); runCurrent()
        assertEquals(1, limited.publishedFrames().size, "Nothing more reaches the relay while it holds us off")
        assertEquals(3, sockets.forUrl(two).single().publishedFrames().size, "The other relay carries on")

        advanceTimeBy(pastFirstHold); runCurrent()
        val after = limited.publishedFrames()
        assertEquals(2, after.size, "The held bell goes once the hold lifts; the heartbeat was dropped")
        assertTrue(after.last().contains(bell.id))
        assertFalse(after.any { it.contains(heartbeat.id) })
        pool.stop()
    }

    @Test fun `a held relay is not reachable and a confirmed send is not offered to it`() = runTest {
        val sockets = FakeSocketFactory()
        val gate = RelayGate()
        val pool = RelayPool(listOf(one), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = gate)
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        assertTrue(pool.reachable())
        gate.rateLimited(one, currentTime)
        assertFalse(pool.reachable())
        supervisorScope {
            val result = async { pool.publishConfirmed(event("04".repeat(32), 1460), 15_000) }
            runCurrent()
            assertFailsWith<PublicationNotOfferedException> { result.await() }
        }
        assertTrue(sockets.opened.single().publishedFrames().isEmpty())
        pool.stop()
    }

    @Test fun `the hold is shared by every pool on the phone`() = runTest {
        val sockets = FakeSocketFactory()
        val gate = RelayGate()
        val room = RelayPool(listOf(one), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = gate)
        val account = RelayPool(listOf(one), sockets, backgroundScope, now = { currentTime }, random = Random(2), gate = gate)
        room.start(); account.start(); runCurrent(); sockets.openAll(); runCurrent()
        val first = event("05".repeat(32), 1464)
        room.publish(first); runCurrent()
        sockets.opened[0].deliverRaw("""["OK","${first.id}",false,"rate-limited: too fast"]"""); runCurrent()
        account.publish(event("06".repeat(32), 20461)); runCurrent()
        assertTrue(sockets.opened[1].publishedFrames().isEmpty())
        room.stop(); account.stop()
    }

    @Test fun `a NOTICE about rate limits holds the relay`() = runTest {
        val sockets = FakeSocketFactory()
        val gate = RelayGate()
        val pool = RelayPool(listOf(one), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = gate)
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        sockets.opened.single().deliverRaw("""["NOTICE","Too many requests, slow down"]"""); runCurrent()
        assertTrue(gate.isHeld(one, currentTime))
        sockets.opened.single().deliverRaw("""["NOTICE","This relay is moderated"]""")
        pool.stop()
    }

    @Test fun `a subscription refused for good is not sent again on reconnect`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf(one, two), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = RelayGate())
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        val collector = backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(1460)))).collect { } }
        runCurrent()
        val first = sockets.forUrl(one).single()
        val id = first.requestedSubscriptions().single()
        first.deliverRaw("""["CLOSED","$id","ERROR: blocked: not on the allow list"]""")
        sockets.forUrl(two).single().deliverRaw("""["CLOSED","$id","auth-required: sign in first"]""")
        runCurrent()
        sockets.opened.toList().forEach { it.drop() }
        advanceTimeBy(5_000); runCurrent()
        sockets.opened.drop(2).forEach { it.open() }; runCurrent()
        assertTrue(sockets.forUrl(one).last().requestedSubscriptions().isEmpty(), "blocked: is not asked again")
        assertTrue(sockets.forUrl(two).last().requestedSubscriptions().isEmpty(), "auth-required: with no grant is not asked again")
        collector.cancel(); pool.stop()
    }

    @Test fun `a subscription closed for a rate limit is sent again when the hold lifts`() = runTest {
        val sockets = FakeSocketFactory()
        val gate = RelayGate()
        val pool = RelayPool(listOf(one), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = gate)
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        val collector = backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(1460)))).collect { } }
        runCurrent()
        val socket = sockets.opened.single()
        val id = socket.requestedSubscriptions().single()
        socket.deliverRaw("""["CLOSED","$id","rate-limited: too many subscriptions"]"""); runCurrent()
        // A new subscription while held waits too.
        val second = backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(1464)))).collect { } }
        runCurrent()
        assertEquals(listOf(id), socket.requestedSubscriptions())
        advanceTimeBy(pastFirstHold); runCurrent()
        assertEquals(3, socket.requestedSubscriptions().size)
        assertTrue(id in socket.requestedSubscriptions().drop(1))
        collector.cancel(); second.cancel(); pool.stop()
    }

    @Test fun `an event refused for good is not offered to that relay again`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf(one, two), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = RelayGate())
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        val retirement = event("07".repeat(32), 1461)
        pool.publish(retirement); runCurrent()
        sockets.forUrl(one).single().deliverRaw("""["OK","${retirement.id}",false,"pow: difficulty 28 required"]"""); runCurrent()
        pool.publish(retirement); runCurrent()
        assertEquals(1, sockets.forUrl(one).single().publishedFrames().size)
        assertEquals(2, sockets.forUrl(two).single().publishedFrames().size)
        pool.stop()
    }

    @Test fun `a refused upgrade is not redialled until the hold lifts`() = runTest {
        val sockets = FakeSocketFactory()
        val gate = RelayGate()
        val pool = RelayPool(listOf(one), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = gate)
        pool.start(); runCurrent()
        sockets.opened.single().drop(RelayRefusal.httpCloseReason(503)); runCurrent()
        assertTrue(gate.isHeld(one, currentTime))
        advanceTimeBy(RelayGate.FIRST_HOLD_MS - 1_000); runCurrent()
        assertEquals(1, sockets.opened.size, "No dial while the relay holds us off")
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, sockets.opened.size)
        pool.stop()
    }

    @Test fun `a healthy socket that drops is redialled after 1 to 2 s, never at once`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf(one), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = RelayGate())
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        sockets.opened.single().drop(); runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(1, sockets.opened.size)
        advanceTimeBy(1_002); runCurrent()
        assertEquals(2, sockets.opened.size)
        pool.stop()
    }

    @Test fun `publishTo offers only to the relays named and acknowledgedBy reports who said OK`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf(one, two), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = RelayGate())
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        val retirement = event("08".repeat(32), 1461)
        pool.publishTo(retirement, setOf(two)); runCurrent()
        assertTrue(sockets.forUrl(one).single().publishedFrames().isEmpty())
        sockets.forUrl(two).single().deliverOk(retirement.id, true); runCurrent()
        assertEquals(setOf(two), pool.acknowledgedBy(retirement.id))
        pool.stop()
    }
}
