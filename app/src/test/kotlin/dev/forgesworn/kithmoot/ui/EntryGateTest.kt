package dev.forgesworn.kithmoot.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `only one of several waiters gets the gate at a time`() = runTest {
        val gate = EntryGate()
        assertTrue(gate.tryAcquire())

        var inside = 0
        var overlapped = false
        repeat(3) {
            launch {
                if (gate.awaitAcquire(timeoutMs = 60_000)) {
                    inside += 1
                    if (inside > 1) overlapped = true
                    inside -= 1
                    gate.release()
                }
            }
        }
        runCurrent()
        gate.release()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(overlapped, "the gate is what stops two rooms opening at once")
        assertEquals(false, gate.held.value)
    }

    @Test
    fun `releasing a gate nobody holds is harmless`() {
        val gate = EntryGate()
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
