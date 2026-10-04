package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedRelayPoolsTest {
    private val defaults = listOf("wss://one.example", "wss://two.example", "wss://three.example")

    private fun TestScope.pools(sockets: FakeSocketFactory) = SharedRelayPools { relays -> RelayPool(relays, sockets, backgroundScope) }

    @Test fun `ten rooms on the same relays hold one socket per relay`() = runTest {
        val sockets = FakeSocketFactory()
        val pools = pools(sockets)
        val held = (1..10).map { pools.acquire(defaults) }
        runCurrent()
        assertEquals(1, held.toSet().size)
        assertEquals(1, pools.size())
        assertEquals(defaults.sorted(), sockets.opened.map { it.url }.sorted())
    }

    @Test fun `the same relays in another order are the same pool`() = runTest {
        val pools = pools(FakeSocketFactory())
        assertSame(pools.acquire(defaults), pools.acquire(defaults.reversed() + defaults.first()))
    }

    @Test fun `a different relay set gets a pool of its own`() = runTest {
        val sockets = FakeSocketFactory()
        val pools = pools(sockets)
        val first = pools.acquire(defaults)
        val second = pools.acquire(defaults.take(2))
        runCurrent()
        assertTrue(first !== second)
        assertEquals(2, pools.size())
        // Kept apart so a relay one room does not list never sees its traffic.
        assertEquals(5, sockets.opened.size)
    }

    @Test fun `a pool stops only when the last room lets go`() = runTest {
        val sockets = FakeSocketFactory()
        val pools = pools(sockets)
        val pool = pools.acquire(defaults)
        pools.acquire(defaults)
        runCurrent()
        pools.release(pool)
        assertFalse(sockets.opened.any { it.closedByPool })
        pools.release(pool)
        assertTrue(sockets.opened.all { it.closedByPool })
        assertEquals(0, pools.size())
        // A later room starts afresh.
        assertTrue(pools.acquire(defaults) !== pool)
    }

    @Test fun `each room's subscription on a shared pool is its own`() = runTest {
        val sockets = FakeSocketFactory()
        val pools = pools(sockets)
        val pool = pools.acquire(defaults)
        pools.acquire(defaults)
        runCurrent()
        sockets.openAll()
        val first = backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(1464)))).toList() }
        val second = backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(14)))).toList() }
        runCurrent()
        val requested = sockets.forUrl(defaults.first()).single().requestedSubscriptions()
        assertEquals(2, requested.toSet().size)
        first.cancel()
        second.cancel()
    }

    @Test fun `a room with a Link relay does not share`() {
        val node = "a".repeat(51) + "q"
        assertTrue(canShareBackgroundPool(defaults) { null })
        assertFalse(canShareBackgroundPool(defaults + "ws://$node/events") { null })
        // Not canonical, but still never handed to a shared public pool.
        assertFalse(canShareBackgroundPool(defaults + "wss://$node/events") { null })
        assertFalse(canShareBackgroundPool(defaults) { url -> if (url == defaults.last()) "route-1" else null })
    }
}
