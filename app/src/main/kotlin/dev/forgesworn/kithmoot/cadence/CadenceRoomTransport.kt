package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.DeadDrop
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
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
    private val retain: suspend (NostrEvent) -> Unit,
    private val queue: (StoredCadenceLease, NostrEvent) -> CompletableFuture<Boolean>,
    private val release: (String) -> Unit,
    /** In-memory lease revision; dispatch must not read the encrypted vault. */
    private val ownershipGeneration: () -> Long,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onFailure: (String) -> Unit = {},
) : RoomTransport {
    override fun receivedEventConfirmsPublication(eventId: String): Boolean =
        inner.receivedEventConfirmsPublication(eventId)
    override fun receivedViaRelays(eventId: String): List<String> = inner.receivedViaRelays(eventId)
    override fun publicationGeneration(): Long = inner.publicationGeneration()

    override fun publish(event: NostrEvent) {
        val lease = delegated(event) ?: return inner.publish(event)
        scope.launch {
            runCatching { handoff(lease, event) }.onFailure {
                onFailure(it.message ?: "Bothy could not queue the quiet message.")
            }
        }
    }

    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
        val lease = delegated(event) ?: return inner.publishConfirmed(event, timeoutMs)
        return try {
            handoff(lease, event)
        } catch (error: Exception) {
            onFailure(error.message ?: "Bothy could not queue the quiet message.")
            throw error
        }
    }

    override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        if (event.kind != KIND_CHAT)
            return inner.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
        val ownership = ownershipGeneration()
        val epoch = DeadDrop.epochIndexAt(now())
        if (leaseAt(epoch) != null) throw PublicationNotOfferedException(
            "Recording delivery cannot be confirmed while a node owns or is taking this phone's quiet schedule. Resolve that schedule before Retry Send.")
        // A box queue receipt is not proof that a relay delivered the notice.
        // Forward only phone-owned dispatch, fenced across lease changes and
        // epoch boundaries; never borrow delegated counters or fall back.
        return inner.publishConfirmedGuarded(event, generation, {
            ownershipGeneration() == ownership && DeadDrop.epochIndexAt(now()) == epoch && stillAllowed()
        }, timeoutMs)
    }

    override fun reachable(): Boolean = inner.reachable()

    override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = inner.queryStored(filters, timeoutMs)
    override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long) = inner.queryAvailable(filters, timeoutMs)
    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = inner.subscribe(filters)
    override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> =
        inner.subscribeReplayed(filters, onReplayComplete)
    override fun describe(): List<String> = inner.describe()
    override fun circleRelays(): Set<String> = inner.circleRelays()
    override suspend fun beginRekey() = inner.beginRekey()
    override suspend fun rekey(roomKey: ByteArray) = inner.rekey(roomKey)
    override fun completeRekey() = inner.completeRekey()
    override fun keepPast(roomKeys: List<ByteArray>) = inner.keepPast(roomKeys)
    override fun publishRecovery(event: NostrEvent) = inner.publishRecovery(event)

    private fun delegated(event: NostrEvent): StoredCadenceLease? {
        if (event.kind != KIND_CHAT) return null
        val epoch = DeadDrop.epochIndexAt(now())
        return leaseAt(epoch)?.also {
            check(it.ownership == CadenceOwnership.BOX_OWNED) { "Bothy's cadence lease outcome is unresolved. Retry it before sending." }
        }
    }

    private suspend fun handoff(lease: StoredCadenceLease, event: NostrEvent): Boolean {
        retain(event)
        val accepted = queue(lease, event).await()
        if (accepted) release(event.id)
        return accepted
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) continuation.resume(value)
        else continuation.resumeWithException(error.cause ?: error)
    }
    continuation.invokeOnCancellation { cancel(true) }
}
