package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI

/** The same gate as grant admission: once the exact close is staged, the
 * keeper cannot issue another grant. Public completion references survive
 * deletion of the descriptor, authority key, devices and pending ciphertext. */
internal class NodeRoomCloser(
    private val node: NodeRoomRecord,
    private val participant: String,
    private val store: NodeStore,
    private val submit: suspend (NodeRoomRecord, JsonObject, ParticipantSigner) -> NodeOperation,
    private val onAccepted: suspend () -> Unit,
    private val allowed: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = NodeKeeperAdmissions.lockFor(participant, node.room)
    private fun custody(): NodeCustody {
        check(allowed())
        return requireNotNull(store.custody(participant, node.room)).also { require(it.node.event.id == node.event.id && it.wire["rootKey"] != null) }
    }
    suspend fun stage(current: () -> EpochKeys): JsonObject = mutex.withLock {
        val saved = custody()
        saved.pendingClose?.let { return@withLock validate(it) }
        val key = requireNotNull(saved.rootKey); val secret = saved.roomSecret
        val epoch = current(); val traffic = EpochKeys(epoch.epoch, epoch.id, epoch.key)
        val next = Entropy.bytes(32)
        try {
            require(epoch.epoch in 0 until MAX_EPOCH)
            requireNotNull(NodeRooms.read(node.wire, now(), node.authority))
            val keeper = NodeKeeperState.read(saved.keeper, node)
            val resetAt = maxOf(now(), keeper.devices.maxOfOrNull { it.grant.createdAt } ?: 0)
            require(resetAt <= now() + 30)
            val operation = Entropy.bytes(16).toHex()
            val expiration = minOf(now() + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE)
            val deadline = minOf(now() + 5, expiration - 1); require(deadline >= now())
            val reset = buildReset(key, node.room, URI(node.origin).host, resetAt)
            val closure = NodeRooms.signClosure(node, key, operation, now())
            val retirement = NodeRooms.signRetirement(node, key, secret, operation, now())
            val rekey = encodeRekeyEvent(node.room, key, traffic, RoomEpoch(epoch.epoch + 1, next), emptyList(), emptyList(), now(),
                closed = true, destruct = true, commit = true, members = emptyList(), roomEnds = expiration)
            val body = validate(buildJsonObject { put("room", node.room); put("operation", operation); put("deadline", deadline); put("reset", reset.toJson()); put("closure", closure.toJson()); put("events", JsonArray(listOf(retirement.toJson(), rekey.toJson()))) })
            store.rememberOperation(NodeOperationRef(NodePair(node.origin, node.serviceKey, participant), operation))
            store.update(participant, node.room) { current -> require(current.pendingClose == null); current.changed { put("pendingClose", body) } }
            body
        } finally { key.fill(0); secret.fill(0); traffic.key.fill(0); next.fill(0) }
    }
    suspend fun retry(): NodeOperation = mutex.withLock {
        val saved = custody(); val body = validate(requireNotNull(saved.pendingClose))
        val operation = NodeEvidence.string(body, "operation")
        store.rememberOperation(NodeOperationRef(NodePair(node.origin, node.serviceKey, participant), operation))
        val key = requireNotNull(saved.rootKey)
        val result = try { submit(node, body, LocalSigner(key)) } finally { key.fill(0) }
        check(allowed())
        // Any validated phase acknowledges durable supervisor intent. The
        // separate signed receipt alone proves that both child teardowns ended.
        require(result.phase in listOf("publishing", "draining", "teardown", "erasing", "done"))
        if (result.phase == "done") require(result.receipt?.operation == operation)
        onAccepted()
        store.forgetRoom(node.room)
        result
    }
    private fun validate(body: JsonObject): JsonObject {
        require(body.keys == setOf("room", "operation", "deadline", "reset", "closure", "events") && NodeEvidence.string(body, "room") == node.room)
        val operation = NodeEvidence.string(body, "operation"); require(NodeEvidence.operation.matches(operation))
        val closure = requireNotNull(NodeRooms.readClosure(body["closure"], node, now())); require(closure.operation == operation)
        val deadline = NodeEvidence.number(body, "deadline"); require(deadline in closure.createdAt..closure.createdAt + 120)
        val reset = ((parseGroupEvent(body["reset"], keepers = listOf(node.authority), servers = listOf(URI(node.origin).host)) as? GroupEventVerdict.Ok)?.parsed as? ParsedReset)
        require(reset?.roomId == node.room)
        val events = body.getValue("events").jsonArray
        require(events.size == 2)
        val parsed = events.map { raw ->
            require(raw.jsonObject.keys == setOf("id", "sig", "pubkey", "kind", "created_at", "tags", "content"))
            NostrEvent.fromJson(raw).also { event ->
                require(Events.verify(event) && event.pubkey == node.authority && event.kind in listOf(1461, 1462) && event.tags.single { it.firstOrNull() == "d" }.let { it.size == 2 && it[1] == node.room })
                val expiration = event.tags.single { it.firstOrNull() == "expiration" }.also { require(it.size == 2) }[1].toLong()
                require(expiration > deadline && expiration <= minOf(closure.createdAt + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE))
            }
        }
        require(parsed.map { it.kind }.toSet() == setOf(1461, 1462) && body.toString().toByteArray(Charsets.UTF_8).size <= 65536)
        return body
    }
}
