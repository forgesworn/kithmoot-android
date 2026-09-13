package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.DeadDrop
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.KIND_CHAT
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Routes quiet chat to a box only during an acknowledged delegated epoch. */
class CadenceRoomTransport(
    private val inner: RoomTransport,
    private val scope: CoroutineScope,
    private val leaseAt: (Long) -> StoredCadenceLease?,
    private val queue: (StoredCadenceLease, NostrEvent) -> CompletableFuture<Boolean>,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onFailure: (String) -> Unit = {},
) : RoomTransport {
    override fun publish(event: NostrEvent) {
        val lease = delegated(event) ?: return inner.publish(event)
        scope.launch {
            runCatching { queue(lease, event).await() }.onFailure {
                onFailure(it.message ?: "Bothy could not queue the quiet message.")
            }
        }
    }

    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
        val lease = delegated(event) ?: return inner.publishConfirmed(event, timeoutMs)
        return queue(lease, event).await()
    }

    override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = inner.queryStored(filters, timeoutMs)
    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = inner.subscribe(filters)
    override fun describe(): List<String> = inner.describe()
    override fun circleRelays(): Set<String> = inner.circleRelays()

    private fun delegated(event: NostrEvent): StoredCadenceLease? {
        if (event.kind != KIND_CHAT) return null
        val epoch = DeadDrop.epochIndexAt(now())
        return leaseAt(epoch)?.also {
            check(it.ownership == CadenceOwnership.BOX_OWNED) { "Bothy's cadence lease outcome is unresolved. Retry it before sending." }
        }
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) continuation.resume(value)
        else continuation.resumeWithException(error.cause ?: error)
    }
    continuation.invokeOnCancellation { cancel(true) }
}
