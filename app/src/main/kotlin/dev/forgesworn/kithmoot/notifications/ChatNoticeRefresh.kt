package dev.forgesworn.kithmoot.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Visibility callbacks must return even while an accepted history or Android's
 * notification service holds the notification monitor. One pending refresh
 * reads the latest flags; a burst never queues a history-sized backlog. */
internal class ChatNoticeRefresh(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    onFailure: () -> Unit,
    refresh: () -> Unit,
) {
    private val pending = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) {
            try {
                for (request in pending) {
                    try { refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                }
            } finally { pending.close() }
        }
    }

    fun request() { pending.trySend(Unit) }
}
