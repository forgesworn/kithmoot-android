package dev.forgesworn.kithmoot.relay

import kotlinx.coroutines.channels.ReceiveChannel

/** The most one batch takes, so a long history is recorded in bounded steps. */
const val MAX_DRAIN_BATCH = 512

/**
 * Hands [record] everything that has queued since it last ran, one batch at a
 * time, until [queue] closes or the caller is cancelled.
 *
 * A room's own messages arrive in a burst when it opens, one per message in
 * its history. Recording each on its own coroutine put hundreds of jobs on the
 * IO dispatcher at once, every one waiting on the same encrypted file, and the
 * call's own reads queued behind them for a minute. One consumer that takes
 * whatever is waiting costs one IO thread and one write per batch.
 */
suspend fun <T> drainInBatches(queue: ReceiveChannel<T>, maxBatch: Int = MAX_DRAIN_BATCH, record: suspend (List<T>) -> Unit) {
    require(maxBatch >= 1)
    for (first in queue) {
        val batch = mutableListOf(first)
        while (batch.size < maxBatch) batch += queue.tryReceive().getOrNull() ?: break
        record(batch)
    }
}
