package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport

/** The caller has decoded these originals against its source room and epochs.
 * Copying never signs them again or extends their original expiration. */
internal suspend fun copyExistingNodeHistory(originals: List<NostrEvent>, target: RoomTransport,
    allowed: () -> Boolean, now: () -> Long) {
    check(allowed())
    require(originals.size <= 100_000 && originals.sumOf { it.toJson().toString().length.toLong() } <= 64L * 1024 * 1024)
    val events = originals.distinctBy { it.id }.filter { event ->
        require(Events.verify(event)) { "Invalid original history signature" }
        val expiration = event.tags.filter { it.firstOrNull() == "expiration" }
        if (expiration.isEmpty()) true else {
            require(expiration.size == 1 && expiration.single().size == 2)
            val end = requireNotNull(expiration.single()[1].toLongOrNull())
            require(end > 0 && end.toString() == expiration.single()[1])
            end > now()
        }
    }
    for (batch in events.chunked(32)) {
        for (event in batch) {
            check(allowed())
            check(target.publishConfirmed(event)) { "The node did not confirm the original history" }
        }
        check(allowed())
        val readback = target.queryStored(listOf(Filter(ids = batch.map { it.id }, limit = batch.size)))
        check(allowed())
        check(batch.all { original -> readback.any { it == original && Events.verify(it) } }) {
            "The node did not retain the original history"
        }
    }
}
