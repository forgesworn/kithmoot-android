package dev.forgesworn.kithmoot.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate that stands between a person and the room they just tapped.
 *
 * The behaviour under test is the one the field complaint was about: a tap
 * that arrives while the last room is still tearing down is never dropped.
 * It waits, and it is carried out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryGateTest {

    @Test
    fun `a free gate is taken at once`() {
        val gate = EntryGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.held.value)
    }

    @Test
    fun `a held gate refuses the quick path`() {
        val gate = EntryGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire(), "two acts must never hold the gate at once")
    }

    @Test
    fun `a wait is carried out once the holder releases`() = runTest {
        val gate = EntryGate()
        assertTrue(gate.tryAcquire())

        // The tap that used to vanish: it arrives while a leave is still
        // publishing its farewell and closing sockets.
        val tap = async { gate.awaitAcquire(timeoutMs = 30_000) }
        runCurrent()
        assertTrue(tap.isActive, "the tap waits rather than being refused")

        gate.release()
        assertTrue(tap.await(), "the waiting tap takes the gate the moment the leave finishes")
        assertTrue(gate.held.value, "and holds it, so nothing else slips in behind it")
    }

    @Test
    fun `a teardown that never finishes gives the tap an answer rather than a wait without end`() = runTest {
        val gate = EntryGate()
        assertTrue(gate.tryAcquire())

        val tap = async { gate.awaitAcquire(timeoutMs = 20_000) }
        advanceTimeBy(20_001)
        assertFalse(tap.await(), "a wedged teardown is a fault to report, not a hang")
        // And the gate is still the holder's: a timed-out waiter must not
        // half-take it and let a second room open over the first.
        assertTrue(gate.held.value)
    }

    @Test
    fun `a release landing in the same instant as the timeout never loses the gate`() = runTest {
        // The hazard is a timeout firing between the flip and the return. The
        // waiter would report a refusal while holding the gate, and every room
        // tap for the rest of the process would then wait thirty seconds on a
        // holder that does not exist.
        repeat(20) { attempt ->
            val gate = EntryGate()
            assertTrue(gate.tryAcquire())
            val tap = async { gate.awaitAcquire(timeoutMs = 1_000) }
            launch {
                delay(1_000)
                gate.release()
            }
            advanceUntilIdle()
            val taken = tap.await()
            // Whatever the race decided, the answer and the gate agree.
            assertEquals(taken, gate.held.value, "attempt $attempt reported $taken with held=${gate.held.value}")
        }
    }

    @Test
    fun `the gate serialises work that actually suspends`() = runTest {
        val gate = EntryGate()
        var inside = 0
        var overlapped = false
        val order = mutableListOf<Int>()

        repeat(3) { n ->
            launch {
                if (!gate.awaitAcquire(timeoutMs = 60_000)) return@launch
                inside += 1
                if (inside > 1) overlapped = true
                // A real critical section: the whole point of the gate is that
                // it holds across suspensions - a room teardown is nothing but
                // suspensions - and a check that never suspends proves nothing.
                delay(1_000)
                order += n
                inside -= 1
                gate.release()
            }
        }
        advanceUntilIdle()

        assertFalse(overlapped, "the gate is what stops two rooms opening at once")
        assertEquals(3, order.size, "every waiter is served, none dropped")
        assertFalse(gate.held.value)
    }

    @Test
    fun `releasing a gate nobody holds is harmless`() {
        val gate = EntryGate()
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}

/**
 * The tap that is waiting is the last one, not the first.
 *
 * Somebody who taps a room, thinks better of it and taps another is asking
 * for the second one. Keeping the first and refusing the second opens the
 * room they decided against, which is worse than the silence it replaced.
 */
class LatestRequestTest {

    @Test
    fun `the first offer owns the waiting`() {
        val slot = LatestRequest<String>()
        assertTrue(slot.offer("standup"), "somebody has to start the wait")
        assertEquals("standup", slot.waiting)
    }

    @Test
    fun `a later offer replaces the one waiting and does not start a second wait`() {
        val slot = LatestRequest<String>()
        slot.offer("standup")
        assertFalse(slot.offer("retro"), "one waiter, not two")
        assertEquals("retro", slot.waiting, "the room they actually want")
    }

    @Test
    fun `taking empties the slot so a later tap starts a fresh wait`() {
        val slot = LatestRequest<String>()
        slot.offer("standup")
        assertEquals("standup", slot.take())
        assertNull(slot.take())
        assertTrue(slot.offer("retro"))
    }
}
