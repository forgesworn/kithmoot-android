package dev.forgesworn.kithmoot.ui.room

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A link with no route can sit on "connecting" on Android without ever
 * reporting "failed", so a call with no relay said nothing while nobody could
 * be heard. These hold the relay line to a link that has been trying for a
 * while, and to nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StuckLinksTest {

    @Test fun `a link still trying after the limit is stuck, and stops being stuck when it connects`() = runTest {
        val connections = MutableStateFlow(mapOf("a" to "connecting"))
        val seen = mutableListOf<Set<String>>()
        val job = launch { stuckLinks(connections, afterMs = 20_000, now = { testScheduler.currentTime }).collect { seen += it } }
        runCurrent()
        assertEquals(emptySet(), seen.last())
        advanceTimeBy(19_999); runCurrent()
        assertEquals(emptySet(), seen.last())
        advanceTimeBy(1); runCurrent()
        assertEquals(setOf("a"), seen.last())
        connections.value = mapOf("a" to "connected")
        runCurrent()
        assertEquals(emptySet(), seen.last())
        job.cancel()
    }

    @Test fun `moving between waiting states keeps the clock running`() = runTest {
        val connections = MutableStateFlow(mapOf("a" to "new"))
        val seen = mutableListOf<Set<String>>()
        val job = launch { stuckLinks(connections, afterMs = 20_000, now = { testScheduler.currentTime }).collect { seen += it } }
        runCurrent()
        advanceTimeBy(8_000); connections.value = mapOf("a" to "checking"); runCurrent()
        advanceTimeBy(8_000); connections.value = mapOf("a" to "connecting"); runCurrent()
        assertEquals(emptySet(), seen.last())
        advanceTimeBy(4_000); runCurrent()
        assertEquals(setOf("a"), seen.last())
        job.cancel()
    }

    @Test fun `a link that connects and then tries again starts a new clock`() = runTest {
        val connections = MutableStateFlow(mapOf("a" to "connecting"))
        val seen = mutableListOf<Set<String>>()
        val job = launch { stuckLinks(connections, afterMs = 20_000, now = { testScheduler.currentTime }).collect { seen += it } }
        runCurrent()
        advanceTimeBy(15_000); connections.value = mapOf("a" to "connected"); runCurrent()
        connections.value = mapOf("a" to "connecting"); runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertEquals(emptySet(), seen.last())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(setOf("a"), seen.last())
        job.cancel()
    }

    @Test fun `each link keeps its own clock`() = runTest {
        val connections = MutableStateFlow(mapOf("a" to "connecting"))
        val seen = mutableListOf<Set<String>>()
        val job = launch { stuckLinks(connections, afterMs = 20_000, now = { testScheduler.currentTime }).collect { seen += it } }
        runCurrent()
        advanceTimeBy(10_000); connections.value = mapOf("a" to "connecting", "b" to "connecting"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(setOf("a"), seen.last())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(setOf("a", "b"), seen.last())
        job.cancel()
    }

    @Test fun `the relay line names a call relay and where to add one`() {
        assertEquals(
            "No call relay (TURN) is set up on this device, so this call may not connect on some networks. Add one in Settings › Connections.",
            RELAY_MISSING_NOTICE,
        )
    }

    @Test fun `a stuck link says so only on a call with no relay, and only while it is still trying`() {
        val trying = mapOf("a" to "connecting")
        assertEquals(RELAY_MISSING_NOTICE, relayMissingNote(onCall = true, hasRelay = false, connections = trying, stuck = setOf("a")))
        assertNull(relayMissingNote(onCall = true, hasRelay = false, connections = trying))
        assertNull(relayMissingNote(onCall = true, hasRelay = true, connections = trying, stuck = setOf("a")))
        assertNull(relayMissingNote(onCall = false, hasRelay = false, connections = trying, stuck = setOf("a")))
        // A stale stuck set never outlives the link's own state.
        assertNull(relayMissingNote(onCall = true, hasRelay = false, connections = mapOf("a" to "connected"), stuck = setOf("a")))
    }
}
