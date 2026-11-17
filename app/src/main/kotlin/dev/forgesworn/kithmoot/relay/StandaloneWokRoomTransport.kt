package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private val STANDALONE_READ_KINDS = setOf(1460, 1462, 20461)
private val STANDALONE_WRITE_KINDS = setOf(1460, 20461)

class StandaloneWokRoomTransport(
    private val inner: RoomTransport,
    private val roomId: String,
) : BoundedRoomTransport {
    override fun publish(event: NostrEvent) = inner.publish(permitted(event))

    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean =
        inner.publishConfirmed(permitted(event), timeoutMs)

    override suspend fun publishConfirmedGuarded(
        event: NostrEvent,
        generation: Long,
        stillAllowed: () -> Boolean,
        timeoutMs: Long,
    ): Boolean = inner.publishConfirmedGuarded(permitted(event), generation, stillAllowed, timeoutMs)

    override suspend fun publishConfirmedBounded(
        event: NostrEvent,
        generation: Long,
        publishBefore: Long,
        stillAllowed: () -> Boolean,
        timeoutMs: Long,
    ): Boolean = inner.publishConfirmedBounded(permitted(event), generation, publishBefore, stillAllowed, timeoutMs)

    override fun publishRecovery(event: NostrEvent) = inner.publishRecovery(permitted(event))

    override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
        requireComplete(filters)
        return inner.queryStored(filters.map(::scoped), timeoutMs)
    }

    override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
        val supported = filters.mapNotNull(::partial)
        return if (supported.isEmpty()) emptyList() else inner.queryAvailable(supported, timeoutMs)
    }

    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
        val supported = filters.mapNotNull(::partial)
        return if (supported.isEmpty()) emptyFlow() else inner.subscribe(supported)
    }

    override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> {
        if (!complete(filters)) return emptyFlow()
        return inner.subscribeReplayed(filters.map(::scoped), onReplayComplete)
    }

    override fun subscribeCatchingUp(filters: () -> List<Filter>, skewSeconds: Long): Flow<NostrEvent> {
        val requested = filters()
        if (!complete(requested)) return emptyFlow()
        return inner.subscribeCatchingUp({ requested.map(::scoped) }, skewSeconds)
    }

    override fun receivedEventConfirmsPublication(eventId: String): Boolean = inner.receivedEventConfirmsPublication(eventId)
    override fun receivedViaRelays(eventId: String): List<String> = inner.receivedViaRelays(eventId)
    override fun reachable(): Boolean = inner.reachable()
    override fun relayAccepted(eventId: String): Boolean = inner.relayAccepted(eventId)
    override fun publicationGeneration(): Long = inner.publicationGeneration()
    override fun guardedConfirmationTimeoutMs(kind: Int): Long = inner.guardedConfirmationTimeoutMs(kind)
    override fun describe(): List<String> = inner.describe()
    override fun circleRelays(): Set<String> = inner.circleRelays()
    override suspend fun beginRekey() = inner.beginRekey()
    override suspend fun rekey(roomKey: ByteArray) = inner.rekey(roomKey)
    override fun completeRekey() = inner.completeRekey()
    override fun keepPast(roomKeys: List<ByteArray>) = inner.keepPast(roomKeys)

    private fun permitted(event: NostrEvent): NostrEvent {
        if (event.kind !in STANDALONE_WRITE_KINDS || event.tags.singleOrNull { it[0] == "d" }?.getOrNull(1) != roomId) {
            throw PublicationNotOfferedException("This standalone Wok route supports text and presence in its granted room only.")
        }
        return event
    }

    private fun scoped(filter: Filter): Filter = filter.copy(kinds = filter.kinds?.filter { it in STANDALONE_READ_KINDS }, tags = filter.tags + ("#d" to listOf(roomId)))

    private fun partial(filter: Filter): Filter? {
        val kinds = filter.kinds?.filter { it in STANDALONE_READ_KINDS }.orEmpty()
        val rooms = filter.tags["#d"].orEmpty().filter { it == roomId }
        return if (kinds.isEmpty() || rooms.isEmpty()) null else filter.copy(kinds = kinds, tags = filter.tags + ("#d" to rooms))
    }

    private fun complete(filters: List<Filter>): Boolean = filters.isNotEmpty() && filters.all { filter ->
        filter.kinds?.isNotEmpty() == true && filter.kinds.all { it in STANDALONE_READ_KINDS } &&
            filter.tags["#d"]?.isNotEmpty() == true && filter.tags["#d"]?.all { it == roomId } == true
    }

    private fun requireComplete(filters: List<Filter>) {
        if (!complete(filters)) throw StoredHistoryUnavailableException()
    }
}
