package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.*

const val NODE_MIGRATION_KIND = 27545
const val NODE_MIGRATION_READY_KIND = 27546
const val NODE_MIGRATION_SECONDS = 600L
const val MAX_NODE_MIGRATION_ENDPOINTS = 16
val NODE_MIGRATION_LABELS = listOf("kithmoot/v1/node-migration", "kithmoot/v1/node-migration-ready")
data class NodeMigrationEndpoint(val device: String, val sid: String) {
    fun toJson() = buildJsonObject { put("device", device); put("sid", sid) }
}
data class NodeMigrationHistory(val events: Long, val digest: String) {
    fun toJson() = buildJsonObject { put("events", events); put("digest", digest) }
}
data class NodeMigrationPreparation(val event: NostrEvent, val wire: JsonObject, val node: NodeRoomRecord,
    val operation: String, val endpoints: List<NodeMigrationEndpoint>, val epoch: Long, val expiresAt: Long)
data class NodeMigrationReady(val event: NostrEvent, val wire: JsonObject, val endpoint: NodeMigrationEndpoint,
    val grant: NostrEvent, val grantWire: JsonObject, val history: NodeMigrationHistory)
data class NodeMigrationCommit(val event: NostrEvent, val wire: JsonObject, val preparation: NodeMigrationPreparation,
    val ready: List<NodeMigrationReady>)

/** Signed migration evidence only. It grants no network or storage authority
 * without a matching node grant and the embedding's durable transition. */
object NodeMigrations {
    private val sidPattern = Regex("^[A-Za-z0-9_-]{8,128}$")
    fun endpointId(endpoint: NodeMigrationEndpoint) = "${endpoint.device}:${endpoint.sid}"
    private fun endpoints(raw: JsonElement?): List<NodeMigrationEndpoint> {
        val list = raw as? JsonArray ?: error("Invalid endpoints")
        require(list.size in 1..MAX_NODE_MIGRATION_ENDPOINTS)
        return list.map {
            val value = NodeWire.shape(it, setOf("device", "sid"))
            NodeMigrationEndpoint(NodeWire.string(value["device"]), NodeWire.string(value["sid"])).also { endpoint ->
                require(NodeWire.key.matches(endpoint.device) && sidPattern.matches(endpoint.sid))
            }
        }.sortedBy(::endpointId).also { require(it.map(::endpointId).distinct().size == it.size) }
    }
    private data class Evidence(val event: NostrEvent, val wire: JsonObject, val body: JsonObject, val expires: Long)
    private fun evidence(raw: JsonElement?, kind: Int, now: Long, authority: String? = null): Evidence {
        require(NodeWire.integer(now))
        val event = NodeWire.event(raw, 131072)
        require(event.kind == kind && event.createdAt <= now + 30 && (authority == null || event.pubkey == authority))
        require(event.tags.size == 2 && event.tags.all { it.size == 2 && it.all { value -> value.length <= 128 } })
        require(NodeWire.key.matches(NodeWire.tag(event, "d")))
        val text = NodeWire.tag(event, "expiration"); val expires = text.toLong()
        require(NodeWire.integer(expires) && expires.toString() == text && expires > now && expires > event.createdAt)
        val body = Json.parseToJsonElement(event.content) as? JsonObject ?: error("Invalid migration")
        require(body.toString() == event.content)
        return Evidence(event, raw as JsonObject, body, expires)
    }
    private fun history(raw: JsonElement?): NodeMigrationHistory {
        val value = NodeWire.shape(raw, setOf("events", "digest"))
        return NodeMigrationHistory(NodeWire.number(value["events"]), NodeWire.string(value["digest"])).also {
            require(it.events <= 1_000_000 && NodeWire.key.matches(it.digest))
        }
    }
    fun digest(ids: List<String>): String {
        require(ids.size <= 1_000_000 && ids.all { NodeWire.key.matches(it) })
        return Digests.sha256(ids.distinct().sorted().joinToString("\n").toByteArray(Charsets.UTF_8)).toHex()
    }
    fun readPreparation(raw: JsonElement?, now: Long, authority: String): NodeMigrationPreparation? = runCatching {
        val e = evidence(raw, NODE_MIGRATION_KIND, now, authority)
        val b = NodeWire.shape(e.body, setOf("v", "profile", "phase", "operation", "node", "endpoints", "epoch"))
        val operation = NodeWire.string(b["operation"]); val epoch = NodeWire.number(b["epoch"])
        require(NodeWire.number(b["v"]) == 1L && NodeWire.string(b["profile"]) == NODE_MIGRATION_LABELS[0]
            && NodeWire.string(b["phase"]) == "prepare" && NodeWire.operation.matches(operation) && epoch <= 0x7fffffff)
        val node = requireNotNull(NodeRooms.read(b["node"], now, authority))
        require(node.existing != null && NodeWire.tag(e.event, "d") == node.room && e.event.createdAt >= node.event.createdAt
            && e.expires == minOf(e.event.createdAt + NODE_MIGRATION_SECONDS, node.endsAt ?: Long.MAX_VALUE))
        val list = endpoints(b["endpoints"])
        require(JsonArray(list.map { it.toJson() }) == b["endpoints"])
        NodeMigrationPreparation(e.event, e.wire, node, operation, list, epoch, e.expires)
    }.getOrNull()
    fun signPreparation(node: NodeRoomRecord, authoritySk: ByteArray, operation: String, endpoints: List<NodeMigrationEndpoint>, epoch: Long,
        now: Long, auxRand: ByteArray = Entropy.bytes(32)): NostrEvent {
        val ordered = endpoints(JsonArray(endpoints.map { it.toJson() }))
        val body = buildJsonObject { put("v", 1); put("profile", NODE_MIGRATION_LABELS[0]); put("phase", "prepare"); put("operation", operation)
            put("node", node.wire); put("endpoints", JsonArray(ordered.map { it.toJson() })); put("epoch", epoch) }
        return Events.sign(authoritySk, NODE_MIGRATION_KIND, now, listOf(listOf("d", node.room), listOf("expiration", minOf(now + NODE_MIGRATION_SECONDS, node.endsAt ?: Long.MAX_VALUE).toString())), body.toString(), auxRand)
            .also { requireNotNull(readPreparation(it.toJson(), now, node.authority)) }
    }
    fun readReady(raw: JsonElement?, preparation: NodeMigrationPreparation, now: Long): NodeMigrationReady? = runCatching {
        val p = requireNotNull(readPreparation(preparation.wire, now, preparation.node.authority))
        val e = evidence(raw, NODE_MIGRATION_READY_KIND, now)
        val b = NodeWire.shape(e.body, setOf("v", "profile", "preparation", "operation", "node", "sid", "grant", "history"))
        require(NodeWire.number(b["v"]) == 1L && NodeWire.string(b["profile"]) == NODE_MIGRATION_LABELS[1]
            && NodeWire.string(b["preparation"]) == p.event.id && NodeWire.string(b["operation"]) == p.operation
            && NodeWire.string(b["node"]) == p.node.event.id && NodeWire.tag(e.event, "d") == p.node.room
            && e.event.createdAt >= p.event.createdAt && e.expires == p.expiresAt)
        val endpoint = NodeMigrationEndpoint(e.event.pubkey, NodeWire.string(b["sid"]))
        require(endpoint in p.endpoints && NodeRooms.verifyGrant(b["grant"], p.node, e.event.pubkey, now))
        NodeMigrationReady(e.event, e.wire, endpoint, NostrEvent.fromJson(b.getValue("grant")), b.getValue("grant").jsonObject, history(b["history"]))
    }.getOrNull()
    fun signReady(preparation: NodeMigrationPreparation, deviceSk: ByteArray, sid: String, grant: JsonObject, history: NodeMigrationHistory,
        now: Long, auxRand: ByteArray = Entropy.bytes(32)): NostrEvent {
        val checked = history(history.toJson())
        val body = buildJsonObject { put("v", 1); put("profile", NODE_MIGRATION_LABELS[1]); put("preparation", preparation.event.id)
            put("operation", preparation.operation); put("node", preparation.node.event.id); put("sid", sid); put("grant", grant); put("history", checked.toJson()) }
        return Events.sign(deviceSk, NODE_MIGRATION_READY_KIND, now, listOf(listOf("d", preparation.node.room), listOf("expiration", preparation.expiresAt.toString())), body.toString(), auxRand)
            .also { requireNotNull(readReady(it.toJson(), preparation, now)) }
    }
    fun readCommit(raw: JsonElement?, now: Long, authority: String): NodeMigrationCommit? = runCatching {
        val e = evidence(raw, NODE_MIGRATION_KIND, now, authority)
        val b = NodeWire.shape(e.body, setOf("v", "profile", "phase", "preparation", "ready"))
        require(NodeWire.number(b["v"]) == 1L && NodeWire.string(b["profile"]) == NODE_MIGRATION_LABELS[0] && NodeWire.string(b["phase"]) == "commit")
        val p = requireNotNull(readPreparation(b["preparation"], e.event.createdAt, authority))
        require(NodeWire.tag(e.event, "d") == p.node.room && e.event.createdAt >= p.event.createdAt
            && e.expires == minOf(e.event.createdAt + NODE_HORIZON_SECONDS, p.node.endsAt ?: Long.MAX_VALUE))
        val entries = b["ready"] as? JsonArray ?: error("Invalid readiness list")
        require(entries.size == p.endpoints.size)
        val ready = entries.map { requireNotNull(readReady(it, p, e.event.createdAt)).also { r -> require(r.event.createdAt <= e.event.createdAt) } }
        require(ready.map { endpointId(it.endpoint) }.toSet() == p.endpoints.map(::endpointId).toSet()
            && ready.map { endpointId(it.endpoint) }.distinct().size == ready.size)
        NodeMigrationCommit(e.event, e.wire, p, ready)
    }.getOrNull()
    fun signCommit(preparation: NodeMigrationPreparation, authoritySk: ByteArray, ready: List<JsonObject>, now: Long,
        auxRand: ByteArray = Entropy.bytes(32)): NostrEvent {
        val body = buildJsonObject { put("v", 1); put("profile", NODE_MIGRATION_LABELS[0]); put("phase", "commit")
            put("preparation", preparation.wire); put("ready", JsonArray(ready)) }
        return Events.sign(authoritySk, NODE_MIGRATION_KIND, now, listOf(listOf("d", preparation.node.room), listOf("expiration", minOf(now + NODE_HORIZON_SECONDS, preparation.node.endsAt ?: Long.MAX_VALUE).toString())), body.toString(), auxRand)
            .also { requireNotNull(readCommit(it.toJson(), now, preparation.node.authority)) }
    }
}
