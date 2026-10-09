package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.RoomMeshTransport

/** Exact already-selected foreground endpoints. Does not create a relay/radio,
 * subscribe, follow incoming control, or own their teardown. Absent lanes stay absent. */
internal class NativeKeeperEndpoints(val binding: RoomRekeyBinding,
    val nearby: RoomMeshTransport?, val internet: RelayPool?) {
    init {
        require((nearby != null) == binding.route.nearby && (internet != null) == binding.route.internet)
        if (nearby != null) require(nearby.hasScope(requireNotNull(binding.meshScope)))
        if (internet != null) require(internet.describe().map(::canonicalRelayUrl).sorted() == binding.relays)
    }
    fun ready(lane: RekeyLane): Boolean = if (lane == RekeyLane.NEARBY)
        nearby?.keeperControlReady() == true else internet?.keeperControlReady() == true
    fun generation(lane: RekeyLane): Long = if (lane == RekeyLane.NEARBY)
        requireNotNull(nearby).publicationGeneration() else requireNotNull(internet).publicationGeneration()
    suspend fun offer(event: NostrEvent, lane: RekeyLane, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        require(binding.permits(lane) && event.kind in setOf(1461, 1462))
        return if (lane == RekeyLane.NEARBY) requireNotNull(nearby).publishKeeperControlGuarded(event, generation, stillAllowed)
        else requireNotNull(internet).publishKeeperControlGuarded(event, generation, stillAllowed, timeoutMs)
    }
}
