package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent

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
