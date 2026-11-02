package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put

/** Source intent retains the exact original. The shared native courier owns
 * delivery retries; the existing epoch vault owns receiver keys and history. */
internal class NodeKeeperRekeys(
    private val node: NodeRoomRecord,
    private val participant: String,
    private val store: NodeStore,
    private val keeper: NodeKeeperAdmissions,
    private val vault: EpochVault,
    private val courier: RoomRekeyCourier,
    private val allowed: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val actions = Mutex()
    suspend fun remove(person: String, live: RoomSession) = actions.withLock {
        check(allowed()); require(person != participant)
        NodeKeeperAdmissions.lockFor(participant, node.room).withLock {
            val held = requireNotNull(store.custody(participant, node.room))
            require(held.pendingClose == null && (held.pendingRemoval == null || held.pendingRemoval == person))
            if (held.pendingRemoval == null) {
                val state = NodeKeeperState.read(held.keeper, node)
                require(person in state.members || person in state.removed)
                val recipients = state.devices.filter { it.participant != person && it.acknowledged &&
                    NodeRooms.verifyGrant(it.grant.event.toJson(), node, it.device, now()) }.map { it.device }.distinct().sorted()
                val current = live.epochKeys()
                try {
                    require(rekeyEventBytes(node.room, node.authority, current.epoch + 1, recipients,
                        (state.removed + person).distinct().sorted(), now(), commit = true, members = state.members.filter { it != person },
                        roomEnds = minOf(now() + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE)) <= RoomRekeyLedger.MAX_EVENT_BYTES)
                } finally { current.key.fill(0) }
                store.update(participant, node.room) { it.changed { put("pendingRemoval", person) } }
            }
        }
        live.holdKeeperTransition(node.room, node.authority, participant, live.identity.devicePubkey)
        prepareRemoval(requireNotNull(store.custody(participant, node.room)), store, keeper, vault, allowed, now)
        dispatch(live)
    }
    suspend fun retry(live: RoomSession? = null) = actions.withLock { dispatch(live) }
    private suspend fun dispatch(live: RoomSession?) = NodeKeeperAdmissions.lockFor(participant, node.room).withLock {
        check(allowed())
        val held = requireNotNull(store.custody(participant, node.room))
        val event = held.pendingRekey ?: return@withLock
        require(held.pendingClose == null)
        // The shared courier's retry window is bounded. A restored source
        // already committed to a later epoch can answer fresh root recovery
        // after that original delivery window ends, without re-signing it.
        if (now() * 1000 < event.createdAt * 1000 + RoomRekeyLedger.TTL_MS) courier.admit(event)
        if (live != null) live.applyKeeperRekey(event, participant, live.identity.devicePubkey)
        // On restart restorePending already committed the exact source notice.
        check(allowed())
        store.update(participant, node.room) { current -> require(current.pendingRekey?.id == event.id); current.without("pendingRekey", "pendingRemoval") }
    }
    companion object {
        /** Durable removal intent precedes every revocation. Restart finishes
         * that same audience change before the room can answer epoch requests. */
        suspend fun prepareRemoval(saved: NodeCustody, store: NodeStore, keeper: NodeKeeperAdmissions,
            vault: EpochVault, allowed: () -> Boolean, now: () -> Long) {
            val person = saved.pendingRemoval ?: return
            if (saved.pendingRekey != null) return
            val node = saved.node
            keeper.remove(person)
            NodeKeeperAdmissions.lockFor(saved.participant, node.room).withLock {
                check(allowed())
                val held = requireNotNull(store.custody(saved.participant, node.room))
                require(held.pendingRemoval == person && held.pendingClose == null)
                if (held.pendingRekey != null) return@withLock
                val state = NodeKeeperState.read(held.keeper, node)
                require(person in state.removed && state.devices.filter { it.participant == person }.all { it.acknowledged && it.grant.status == "revoked" })
                val recipients = state.devices.filter { it.acknowledged && it.participant in state.members &&
                    NodeRooms.verifyGrant(it.grant.event.toJson(), node, it.device, now()) }.map { it.device }.distinct().sorted()
                require(held.device in recipients)
                val durable = requireNotNull(vault.get(node.room))
                val key = requireNotNull(held.rootKey); val next = Entropy.bytes(32)
                val current = deriveEpoch(RoomEpoch(durable.currentEpoch, durable.currentSecret))
                try {
                    require(durable.phase == EpochPhase.ACTIVE && durable.pending == null && durable.authority == node.authority)
                    val expiration = minOf(now() + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE)
                    require(rekeyEventBytes(node.room, node.authority, current.epoch + 1, recipients, state.removed,
                        now(), commit = true, members = state.members, roomEnds = expiration) <= RoomRekeyLedger.MAX_EVENT_BYTES)
                    val event = encodeRekeyEvent(node.room, key, current, RoomEpoch(current.epoch + 1, next), recipients,
                        state.removed, now(), commit = true, members = state.members, roomEnds = expiration)
                    store.update(saved.participant, node.room) { it.changed { put("pendingRekey", event.toJson()) } }
                } finally { current.key.fill(0); key.fill(0); next.fill(0); durable.currentSecret.fill(0); durable.pending?.secret?.fill(0) }
            }
        }
        /** Called before deriving the opening epoch, never from relay data. */
        fun restorePending(saved: NodeCustody, vault: EpochVault, now: Long) {
            val event = saved.pendingRekey ?: return
            val target = requireNotNull(peekRekeyEpoch(event, saved.node.room, saved.node.authority))
            val durable = requireNotNull(vault.get(saved.node.room))
            try {
                require(durable.authority == saved.node.authority)
                if (durable.currentEpoch >= target) {
                    require(durable.currentEpoch == target && (durable.activationCause == event.id || vault.rekeyAt(saved.node.room, target)?.id == event.id))
                    return
                }
                if (durable.pending != null) {
                    require(durable.pending.epoch == target && durable.pending.cause == event.id)
                    vault.activate(saved.node.room, target, now)
                } else {
                    val current = deriveEpoch(RoomEpoch(durable.currentEpoch, durable.currentSecret)); val device = saved.deviceKey
                    try {
                        val notice = requireNotNull(decodeRekeyEvent(event, saved.node.room, saved.node.authority, current, device))
                        require(!notice.closed && notice.secret != null)
                        vault.follow(saved.node.room, notice, event.id, now) ?: error("The node rekey could not be restored")
                        notice.secret?.fill(0)
                    } finally { current.key.fill(0); device.fill(0) }
                }
                vault.remember(saved.node.room, emptyList(), listOf(event))
            } finally { durable.currentSecret.fill(0); durable.pending?.secret?.fill(0) }
        }
    }
}
