package dev.forgesworn.kithmoot.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A room's history arrives in a burst at open; it is recorded in a few batches, not one job per message. */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchDrainTest {
    @Test fun `takes whatever has queued as one batch`() = runTest {
        val queue = Channel<Int>(Channel.UNLIMITED)
        (1..5).forEach { queue.trySend(it) }
        queue.close()
        val batches = mutableListOf<List<Int>>()
        drainInBatches(queue) { batches += it }
        assertEquals(listOf(listOf(1, 2, 3, 4, 5)), batches)
    }

    @Test fun `a batch is bounded`() = runTest {
        val queue = Channel<Int>(Channel.UNLIMITED)
        (1..5).forEach { queue.trySend(it) }
        queue.close()
        val batches = mutableListOf<List<Int>>()
        drainInBatches(queue, maxBatch = 2) { batches += it }
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), batches)
    }

    @Test fun `what arrives while a batch is recorded goes in the next one`() = runTest {
        val queue = Channel<Int>(Channel.UNLIMITED)
        queue.trySend(1)
        val batches = mutableListOf<List<Int>>()
        val job = launch {
            drainInBatches(queue) { batch ->
                batches += batch
                if (batch == listOf(1)) { queue.trySend(2); queue.trySend(3) }
            }
        }
        runCurrent()
        queue.close()
        job.join()
        assertEquals(listOf(listOf(1), listOf(2, 3)), batches)
    }
}
