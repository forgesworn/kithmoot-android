package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelayPool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Current verified guest devices eligible for a Bothy circle grant. */
fun currentCircleGuestDevices(
    events: List<NostrEvent>,
    roomId: String,
    roomKey: ByteArray,
    guest: String,
    now: Long,
    freshForSeconds: Long,
): List<Pair<String, String>> = events
    .mapNotNull { decodeRosterEvent(it, roomId, roomKey, now) }
    .filter { it.participant == guest }
    .groupBy { it.device }
    .mapNotNull { (_, entries) -> entries.maxByOrNull { it.updatedAt } }
    .filter { !it.left && it.updatedAt >= now - freshForSeconds }
    .sortedBy { it.device }
    .map { it.participant to it.device }

/** Observe fresh signed guest devices, including arrivals after an empty replay. */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun observeCircleGuestDevices(
    source: RelayPool,
    roomId: String,
    roomKey: ByteArray,
    guest: String,
    now: () -> Long,
    freshForSeconds: Long,
    timeoutMs: Long = 25_000,
    settleMs: Long = 1_000,
): List<Pair<String, String>> = coroutineScope {
    require(timeoutMs > 0 && settleMs > 0)
    val events = linkedMapOf<String, NostrEvent>()
    var latest = emptyList<Pair<String, String>>()
    val incoming = source.subscribe(
        listOf(Filter(kinds = listOf(KIND_ROSTER), tags = mapOf("#d" to listOf(roomId)))),
    ).produceIn(this)
    try {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val event = if (latest.isEmpty()) incoming.receiveCatching().getOrNull()
                else withTimeoutOrNull(settleMs) { incoming.receiveCatching().getOrNull() }
                if (event == null) break
                events[event.id] = event
                if (events.size > 512) events.remove(events.keys.first())
                latest = currentCircleGuestDevices(events.values.toList(), roomId, roomKey, guest, now(), freshForSeconds)
            }
        }
    } finally {
        incoming.cancel()
    }
    currentCircleGuestDevices(events.values.toList(), roomId, roomKey, guest, now(), freshForSeconds)
}
