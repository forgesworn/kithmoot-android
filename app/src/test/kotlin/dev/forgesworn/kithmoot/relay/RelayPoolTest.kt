package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RelayPoolTest {

    private val relays = listOf("wss://one.example", "wss://two.example", "wss://three.example")

    private fun event(id: String) = NostrEvent(
        kind = 20461,
        createdAt = 1799995000,
        tags = listOf(listOf("d", "room")),
        content = "opaque",
        pubkey = "aa".repeat(32),
        id = id,
        sig = "cc".repeat(64),
    )

    @Test
    fun `the same event from every relay is delivered once`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()

        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()
        // Three relays, one event, three copies arriving. This is the normal
        // case, not an unusual one: publishing everywhere means receiving
        // everywhere.
        sockets.opened.forEach { it.deliverEvent(subscriptionId, event("ff".repeat(32))) }
        runCurrent()

        assertEquals(1, received.size)
    }

    @Test
    fun `distinct events all get through`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()

        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()
        sockets.opened[0].deliverEvent(subscriptionId, event("11".repeat(32)))
        sockets.opened[1].deliverEvent(subscriptionId, event("22".repeat(32)))
        sockets.opened[2].deliverEvent(subscriptionId, event("33".repeat(32)))
        runCurrent()

        assertEquals(3, received.size)
    }

    @Test
    fun `a publish goes to every connected relay`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        pool.publish(event("44".repeat(32)))

        assertEquals(3, sockets.opened.count { it.publishedFrames().size == 1 })
    }

    @Test
    fun `a publish made before any relay is up is not lost`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()

        // Joining a room means announcing at once, which is always a few hundred
        // milliseconds before the first socket finishes opening. Losing this
        // publish means losing the announce that tells the room you arrived.
        pool.publish(event("55".repeat(32)))
        assertTrue(sockets.opened.all { it.publishedFrames().isEmpty() })

        sockets.openAll()
        assertEquals(3, sockets.opened.count { it.publishedFrames().size == 1 })
    }

    @Test
    fun `a queued publish is dropped once it is stale`() = runTest {
        val sockets = FakeSocketFactory()
        val policy = RelayPolicy(outboxTtlMs = 5_000)
        val pool = RelayPool(
            listOf("wss://one.example"),
            sockets,
            backgroundScope,
            policy,
            now = { currentTime },
            random = Random(1),
        )
        pool.start()
        runCurrent()
        pool.publish(event("66".repeat(32)))

        advanceTimeBy(6_000)
        runCurrent()
        sockets.opened.first().open()

        // A stale signalling frame is worse than a dropped one: the negotiation
        // it belonged to has moved on.
        assertTrue(sockets.opened.first().publishedFrames().isEmpty())
    }

    @Test
    fun `a dropped relay is reconnected and its subscriptions re-sent`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(
            listOf("wss://one.example"),
            sockets,
            backgroundScope,
            now = { currentTime },
            random = Random(1),
        )
        pool.start()
        runCurrent()
        sockets.opened.first().open()

        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { } }
        runCurrent()
        val first = sockets.opened.first()
        val subscriptionId = first.requestedSubscriptions().single()

        first.drop()
        assertEquals(emptySet(), pool.connected.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, sockets.opened.size, "the pool should have reopened the socket")

        val second = sockets.opened[1]
        second.open()
        runCurrent()

        // Subscriptions do not survive a dropped socket. A client that does not
        // re-send them goes silently deaf while still looking connected.
        assertEquals(listOf(subscriptionId), second.requestedSubscriptions())
        assertEquals(setOf("wss://one.example"), pool.connected.value)
    }

    @Test
    fun `backoff grows and is capped`() {
        val policy = RelayPolicy(baseDelayMs = 500, maxDelayMs = 30_000)
        assertEquals(500, policy.delayFor(0))
        assertEquals(1_000, policy.delayFor(1))
        assertEquals(4_000, policy.delayFor(3))
        assertEquals(30_000, policy.delayFor(9))
        assertEquals(30_000, policy.delayFor(1_000))
    }

    @Test
    fun `one relay going down leaves the others carrying the room`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()
        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()

        sockets.forUrl("wss://one.example").first().drop()
        runCurrent()

        // No single relay is load-bearing. That is the point of the project.
        sockets.forUrl("wss://two.example").first().deliverEvent(subscriptionId, event("77".repeat(32)))
        runCurrent()
        assertEquals(1, received.size)
        assertEquals(2, pool.connected.value.size)
    }

    @Test
    fun `a hostile frame does not kill the socket`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(
            listOf("wss://one.example"),
            sockets,
            backgroundScope,
            now = { currentTime },
            random = Random(1),
        )
        pool.start()
        runCurrent()
        sockets.opened.first().open()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()
        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()

        sockets.opened.first().deliverRaw("this is not json")
        sockets.opened.first().deliverRaw("""["EVENT","$subscriptionId",{"kind":"nonsense"}]""")
        sockets.opened.first().deliverEvent(subscriptionId, event("88".repeat(32)))
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(setOf("wss://one.example"), pool.connected.value)
    }
}
