package dev.forgesworn.kithmoot.node

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*

/** Private custody is separate from the public pairing and closure receipts.
 * Historical signatures can be read after expiry for recovery/local deletion;
 * they confer no live permission. Every live adapter checks the current clock. */
internal class NodeCustody private constructor(val wire: JsonObject, val node: NodeRoomRecord, val participant: String, val device: String) {
    val phase get() = NodeEvidence.string(wire, "phase")
    val roomSecret get() = NodeEvidence.string(wire, "secret").hexToBytes()
    val deviceKey get() = NodeEvidence.string(wire, "deviceKey").hexToBytes()
    val rootKey get() = wire["rootKey"]?.jsonPrimitive?.content?.hexToBytes()
    val credential get() = NostrEvent.fromJson(wire.getValue("credential"))
    val grant get() = wire["grant"] as? JsonObject
    val epochHint get() = wire["epochHint"]?.jsonPrimitive?.int ?: 0
    val receiver get() = wire["receiver"] as? JsonObject
    val keeper get() = wire["keeper"] as? JsonObject
    val pendingClose get() = wire["pendingClose"] as? JsonObject
    val pendingRekey get() = wire["pendingRekey"]?.let(NostrEvent::fromJson)
    val pendingRemoval get() = wire["pendingRemoval"]?.jsonPrimitive?.content
    fun without(vararg fields: String): NodeCustody = read(JsonObject(wire.filterKeys { it !in fields }))
    fun changed(block: JsonObjectBuilder.() -> Unit): NodeCustody = read(buildJsonObject { wire.forEach { (key, value) -> put(key, value) }; block() })
    override fun toString() = "NodeCustody(redacted)"
    companion object {
        fun read(raw: JsonElement): NodeCustody {
            val value = raw.jsonObject
            require(value.keys.containsAll(setOf("v", "node", "participant", "deviceKey", "credential", "secret", "phase"))
                && value.keys.all { it in setOf("v", "node", "participant", "deviceKey", "credential", "secret", "phase", "rootKey", "grant", "epochHint", "receiver", "keeper", "pendingClose", "pendingRekey", "rekeyJournal", "pendingRemoval") }
                && NodeEvidence.number(value, "v") == 1L && NodeEvidence.string(value, "phase") in listOf("creating", "admitted"))
            val record = NostrEvent.fromJson(value.getValue("node"))
            val node = requireNotNull(NodeRooms.read(value["node"], record.createdAt))
            val participant = NodeEvidence.string(value, "participant").also { require(NodeEvidence.key.matches(it)) }
            fun key(name: String): ByteArray = NodeEvidence.string(value, name).also { require(NodeEvidence.key.matches(it)) }.hexToBytes()
            val deviceKey = key("deviceKey")
            val device = try { Schnorr.publicKeyHex(deviceKey) } finally { deviceKey.fill(0) }
            require(device != node.authority && device != node.serviceKey)
            val secret = key("secret")
            try { val room = deriveRoom(secret); try { require(room.roomId == node.room) } finally { room.roomKey.fill(0) } } finally { secret.fill(0) }
            val credential = NostrEvent.fromJson(value.getValue("credential"))
            val proof = verifyDeviceCredential(credential, node.room, credential.createdAt)
            require(proof is CredentialCheck.Valid && proof.participant == participant && proof.device == device)
            value["rootKey"]?.let { val root = key("rootKey"); try { require(Schnorr.publicKeyHex(root) == node.authority) } finally { root.fill(0) } }
            value["grant"]?.let { val event = NostrEvent.fromJson(it); require(NodeRooms.verifyGrant(it, node, device, event.createdAt)) }
            if (NodeEvidence.string(value, "phase") == "admitted") require(value["grant"] != null)
            value["epochHint"]?.let { require(NodeEvidence.number(value, "epochHint") <= MAX_EPOCH) }
            value["receiver"]?.let { require(it is JsonObject && GrantReceiver.restore(node.authority, node.room, device, it).snapshot() == it) }
            value["keeper"]?.let { require(it is JsonObject && value["rootKey"] != null); NodeKeeperState.read(it, node) }
            value["rekeyJournal"]?.let { require(value["rootKey"] != null && NodeEvidence.bool(value, "rekeyJournal")) }
            value["pendingRemoval"]?.let { require(value["rootKey"] != null && NodeEvidence.key.matches(NodeEvidence.string(value, "pendingRemoval")) && NodeEvidence.string(value, "pendingRemoval") != participant) }
            value["pendingRekey"]?.let {
                val event = NostrEvent.fromJson(it)
                require(value["rootKey"] != null && Events.verify(event) && peekRekeyEpoch(event, node.room, node.authority) != null)
                val deadline = event.tags.single { tag -> tag.firstOrNull() == "expiration" }.also { tag -> require(tag.size == 2) }[1].toLong()
                require(deadline > event.createdAt && deadline <= minOf(event.createdAt + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE))
            }
            value["pendingClose"]?.let {
                require(it is JsonObject && value["rootKey"] != null && NodeEvidence.string(it, "room") == node.room && NodeEvidence.operation.matches(NodeEvidence.string(it, "operation")))
            }
            return NodeCustody(value, node, participant, device)
        }
    }
}

internal data class NodeOperationRef(val pair: NodePair, val operation: String) {
    init { require(NodeEvidence.operation.matches(operation)) }
    fun toJson() = buildJsonObject { put("v", 1); put("origin", pair.origin); put("serviceKey", pair.serviceKey); put("owner", pair.owner); put("operation", operation) }
    companion object {
        fun read(value: JsonObject): NodeOperationRef {
            require(value.keys == setOf("v", "origin", "serviceKey", "owner", "operation") && NodeEvidence.number(value, "v") == 1L)
            return NodeOperationRef(NodePair(NodeEvidence.string(value, "origin"), NodeEvidence.string(value, "serviceKey"), NodeEvidence.string(value, "owner")), NodeEvidence.string(value, "operation"))
        }
    }
}

/** Atomic encrypted stores. No corruption-to-empty fallback and no automatic
 * eviction: a failed save must never replace a room/device/authority identity. */
internal class NodeStore(private val pairs: RoomStorage, private val rooms: RoomStorage, private val operations: RoomStorage) {
    companion object {
        private val monitor = Any()
        fun android(context: Context) = NodeStore(
            EncryptedRoomStorage(context, "kithmoot.node-pairs.v1", 262144, "KithMoot node pairs v1".toByteArray()),
            EncryptedRoomStorage(context, "kithmoot.node-rooms.v1", 4 * 1024 * 1024, "KithMoot node rooms v1".toByteArray()),
            EncryptedRoomStorage(context, "kithmoot.node-operations.v1", 262144, "KithMoot node operations v1".toByteArray()))
    }
    private fun read(storage: RoomStorage): JsonObject = storage.read()?.let { bytes ->
        try { require(bytes.size <= 4 * 1024 * 1024); val raw = NodeEvidence.utf8(bytes); Json.parseToJsonElement(raw).jsonObject.also { require(it.toString() == raw) } }
        finally { bytes.fill(0) }
    } ?: buildJsonObject {}
    private fun write(storage: RoomStorage, values: Map<String, JsonElement>) {
        require(values.size <= 100)
        val bytes = JsonObject(values).toString().toByteArray(Charsets.UTF_8)
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }
    fun pair(account: String): NodePair? = synchronized(monitor) { read(pairs)[account]?.jsonObject?.let {
        require(it.keys == setOf("v", "origin", "serviceKey", "owner") && NodeEvidence.number(it, "v") == 1L && NodeEvidence.string(it, "owner") == account)
        NodePair(NodeEvidence.string(it, "origin"), NodeEvidence.string(it, "serviceKey"), account)
    } }
    fun savePair(pair: NodePair) = synchronized(monitor) { write(pairs, read(pairs) + (pair.owner to buildJsonObject { put("v", 1); put("origin", pair.origin); put("serviceKey", pair.serviceKey); put("owner", pair.owner) })) }
    fun unpair(account: String) = synchronized(monitor) { write(pairs, read(pairs) - account) }
    fun custody(participant: String, room: String): NodeCustody? = synchronized(monitor) { read(rooms)["$participant:$room"]?.let { NodeCustody.read(it).also { saved -> require(saved.participant == participant && saved.node.room == room) } } }
    fun recoverableCreations(participant: String): List<NodeCustody> = synchronized(monitor) {
        read(rooms).values.map(NodeCustody::read).filter { it.participant == participant && it.wire["rootKey"] != null && it.pendingClose == null }
    }
    fun update(participant: String, room: String, change: (NodeCustody) -> NodeCustody): NodeCustody = synchronized(monitor) {
        val current = requireNotNull(custody(participant, room)) { "Node room custody is unavailable" }
        val next = change(current)
        require(next.participant == participant && next.node.room == room)
        save(next); next
    }
    /** An explicit verified admission may create custody, while ordinary
     * polling updates still require an existing record and cannot resurrect it. */
    fun admit(participant: String, room: String, change: (NodeCustody?) -> NodeCustody): NodeCustody = synchronized(monitor) {
        val next = change(custody(participant, room))
        require(next.participant == participant && next.node.room == room)
        save(next); next
    }
    fun save(custody: NodeCustody) = synchronized(monitor) {
        val values = read(rooms); val id = "${custody.participant}:${custody.node.room}"
        values[id]?.let { previous ->
            val old = NodeCustody.read(previous)
            require(old.node.event.id == custody.node.event.id && old.device == custody.device && old.participant == custody.participant)
            require(old.phase != "admitted" || custody.phase == "admitted")
            require(old.wire["secret"] == custody.wire["secret"])
            old.wire["rootKey"]?.let { require(it == custody.wire["rootKey"]) }
            old.wire["rekeyJournal"]?.let { require(it == custody.wire["rekeyJournal"]) }
            // Pending closure retries retain the exact signed body, even after lost ACKs.
            old.pendingClose?.let { require(it == custody.pendingClose) }
            old.keeper?.let { previous ->
                val before = NodeKeeperState.read(previous, old.node)
                val after = NodeKeeperState.read(custody.keeper, custody.node)
                require(after.removed.containsAll(before.removed))
                for (row in before.devices) {
                    val next = requireNotNull(after.devices.singleOrNull { it.device == row.device })
                    require(next.participant == row.participant && next.grant.grantId == row.grant.grantId && next.grant.createdAt >= row.grant.createdAt)
                    if (row.grant.status == "revoked") require(next.grant.status == "revoked")
                    if (next.grant.createdAt == row.grant.createdAt) require(next.grant.event == row.grant.event)
                }
            }
        }
        write(rooms, values + (id to custody.wire))
    }
    fun forgetRoom(room: String) = synchronized(monitor) {
        require(NodeEvidence.key.matches(room))
        write(rooms, read(rooms).filterValues { NodeCustody.read(it).node.room != room })
    }
    fun rememberOperation(ref: NodeOperationRef) = synchronized(monitor) {
        val values = read(operations); val id = "${ref.pair.owner}:${ref.operation}"
        values[id]?.let { require(NodeOperationRef.read(it.jsonObject) == ref) }
        write(operations, values + (id to ref.toJson()))
    }
    fun operations(account: String): List<NodeOperationRef> = synchronized(monitor) { read(operations).values.map { NodeOperationRef.read(it.jsonObject) }.filter { it.pair.owner == account } }
    fun forgetOperation(account: String, operation: String) = synchronized(monitor) { write(operations, read(operations) - "$account:$operation") }
}
