package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PeerHandoverTest {
    @Test fun `working media retires one second after its current replacement connects`() = runTest {
        val closed = mutableListOf<Any>(); val handover = PeerHandover<Any>(backgroundScope, closed::add)
        val old = Any(); val fresh = Any()
        handover.replace("peer", old, fresh, true, 20_000); runCurrent()
        assertTrue(handover.owns("peer", old)); assertTrue(closed.isEmpty())
        handover.connected("peer", fresh); runCurrent(); advanceTimeBy(999); runCurrent()
        assertTrue(closed.isEmpty()); advanceTimeBy(1); runCurrent()
        assertEquals(listOf(old), closed); assertTrue(handover.snapshot().isEmpty())
    }
    @Test fun `repeated repairs retain one old transport and never extend its deadline`() = runTest {
        val closed = mutableListOf<Any>(); val handover = PeerHandover<Any>(backgroundScope, closed::add)
        val old = Any(); val first = Any(); val second = Any()
        handover.replace("peer", old, first, true, 20_000); runCurrent(); advanceTimeBy(19_000)
        handover.replace("peer", first, second, false, 20_000)
        handover.connected("peer", first); runCurrent()
        assertEquals(listOf(first), closed); assertEquals(listOf("peer" to old), handover.snapshot())
        advanceTimeBy(1_000); runCurrent(); assertEquals(listOf(first, old), closed)
        assertTrue(handover.snapshot().isEmpty())
    }
    @Test fun `removal closes retained media immediately and timers cannot close it twice`() = runTest {
        val closed = mutableListOf<Any>(); val handover = PeerHandover<Any>(backgroundScope, closed::add)
        val old = Any(); val other = Any()
        handover.replace("peer", old, Any(), true, 20_000)
        handover.replace("other", other, Any(), true, 20_000); runCurrent()
        handover.remove("peer"); assertEquals(listOf(old), closed)
        handover.clear(); advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(old, other), closed)
    }
    @Test fun `unconnected peers close immediately and grace cannot exceed the old login lifetime`() = runTest {
        val closed = mutableListOf<Any>(); val handover = PeerHandover<Any>(backgroundScope, closed::add)
        val failed = Any(); val old = Any()
        handover.replace("failed", failed, Any(), false, 20_000); assertEquals(listOf(failed), closed)
        handover.replace("peer", old, Any(), true, 500); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(listOf(failed, old), closed)
    }
}
