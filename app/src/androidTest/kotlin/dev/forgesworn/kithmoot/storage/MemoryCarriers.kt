package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.mls.VmlsCarrier
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

/** One relay's worth of ephemeral events in memory, for two runtimes in one process: what was published reaches every subscriber then listening. */
class MemoryCarriers {
    private val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
    val published = mutableListOf<NostrEvent>()

    fun open(): VmlsCarrier = object : VmlsCarrier {
        override suspend fun publish(event: NostrEvent): Boolean {
            synchronized(published) { published += event }
            events.emit(event)
            return true
        }

        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events.filter { event -> filters.any { matches(it, event) } }

        override fun close() = Unit
    }

    private fun matches(filter: Filter, event: NostrEvent): Boolean =
        (filter.kinds == null || event.kind in filter.kinds!!) &&
            (filter.authors == null || event.pubkey in filter.authors!!) &&
            filter.tags.all { (name, values) -> event.tagValue(name.removePrefix("#")) in values }
}
