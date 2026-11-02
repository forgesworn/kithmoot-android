package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.sync.withLock

/** Node epoch recovery never enrols a device. It answers only an exact,
 * acknowledged, live keeper grant, using the existing durable epoch journal. */
internal class NodeRootEpochDesk(
    private val node: NodeRoomRecord,
    private val participant: String,
    private val store: NodeStore,
    private val vault: EpochVault,
    private val allowed: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun answer(event: NostrEvent): NostrEvent? = NodeKeeperAdmissions.lockFor(participant, node.room).withLock {
        if (!allowed() || node.endsAt?.let { now() >= it } == true) return@withLock null
        val saved = store.custody(participant, node.room) ?: return@withLock null
        if (saved.node.event.id != node.event.id || saved.pendingClose != null || saved.pendingRekey != null || saved.pendingRemoval != null) return@withLock null
        val key = saved.rootKey ?: return@withLock null
        val secret = saved.roomSecret
        val room = deriveRoom(secret)
        try {
            val request = decodeEpochRequest(event, node.room, key, room.roomKey, now()) ?: return@withLock null
            val state = NodeKeeperState.read(saved.keeper, node)
            val device = state.devices.singleOrNull { it.device == request.device && it.participant == request.participant }
                ?: return@withLock null
            if (request.participant !in state.members || request.participant in state.removed || !device.acknowledged
                || !NodeRooms.verifyGrant(device.grant.event.toJson(), node, request.device, now())) return@withLock null
            val durable = vault.get(node.room) ?: return@withLock null
            try {
                if (durable.authority != node.authority || durable.phase != EpochPhase.ACTIVE || durable.pending != null
                    || request.participant in durable.removed || !allowed()) return@withLock null
                encodeEpochGrant(node.room, key, request.device, request.request, now(),
                    epoch = RoomEpoch(durable.currentEpoch, durable.currentSecret), removed = durable.removed,
                    members = state.members, roomEnds = minOf(now() + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE))
            } finally { durable.currentSecret.fill(0); durable.pending?.secret?.fill(0) }
        } finally { key.fill(0); secret.fill(0); room.roomKey.fill(0) }
    }
}
