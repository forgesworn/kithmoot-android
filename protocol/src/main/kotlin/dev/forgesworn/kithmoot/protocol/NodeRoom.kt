package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

const val NODE_RECORD_KIND = 27542
const val NODE_CLOSE_KIND = 27543
const val NODE_RECEIPT_KIND = 27544
const val NODE_RETIREMENT_KIND = 1461
const val NODE_LINK_VERSION = 6
const val NODE_ASK_SECONDS = 600L
const val NODE_HORIZON_SECONDS = 30L * 86400
val NODE_ROOM_LABELS = listOf("kithmoot/v1/node-room", "kithmoot/v1/node-ask", "kithmoot/v1/node-answer", "kithmoot/v1/node-retirement", "kithmoot/v1/node-room-existing")

data class NodeTurnPorts(val udpTcp: Int, val tls: Int) {
    init { require(udpTcp in 1024..65535 && tls in 1024..65535 && udpTcp != tls) }
    fun toJson() = buildJsonObject { put("udpTcp", udpTcp); put("tls", tls) }
    companion object { val DEFAULT = NodeTurnPorts(3478, 5349) }
}

data class NodeExistingRoomPolicy(val destruct: Boolean, val policy: JsonObject? = null)

/** Public signed policy only; never a traffic key or usable grant. */
data class NodeRoomRecord(val event: NostrEvent, val wire: JsonObject, val room: String, val authority: String,
    val origin: String, val serviceKey: String, val endsAt: Long?, val relay: String, val iceUrls: List<String>, val turnUrls: List<String>,
    val turnPorts: NodeTurnPorts = NodeTurnPorts.DEFAULT, val existing: NodeExistingRoomPolicy? = null) {
    override fun toString() = "NodeRoomRecord(redacted)"
}
data class CompactNodeClosure(val id: String, val sig: String, val createdAt: Long, val operation: String, val expiration: String) {
    fun toJson() = buildJsonObject { put("id", id); put("sig", sig); put("createdAt", createdAt); put("operation", operation); put("expiration", expiration) }
}
data class NodeCloseReceipt(val operation: String, val published: Boolean, val callsRevoked: Boolean = true,
    val relayPurged: Boolean = true, val eventsDeleted: Long, val completedAt: Long)

/** Strict helpers shared only by this profile. Existing invitation/Spry rules
 * remain separate, including their independently versioned URL policies. */
internal object NodeWire {
    val key = Regex("^[0-9a-f]{64}$")
    val operation = Regex("^[0-9a-f]{32}$")
    val eventKeys = setOf("id", "pubkey", "sig", "kind", "created_at", "content", "tags")
    fun integer(n: Long) = n in 0..9_007_199_254_740_991L
    fun string(value: JsonElement?): String = requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
    fun number(value: JsonElement?): Long = requireNotNull((value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull).also { require(integer(it)) }
    fun boolean(value: JsonElement?): Boolean = requireNotNull((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull)
    fun shape(value: JsonElement?, required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
        val obj = value as? JsonObject ?: error("Invalid node evidence")
        require(obj.keys.containsAll(required) && obj.keys.all { it in required || it in optional }); return obj
    }
    fun json(raw: String, required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
        val obj = shape(Json.parseToJsonElement(raw), required, optional)
        require(obj.toString() == raw) { "Non-canonical node evidence" }; return obj
    }
    fun event(value: JsonElement?, limit: Int = 65536): NostrEvent {
        val raw = shape(value, eventKeys)
        require(raw.toString().toByteArray(Charsets.UTF_8).size <= limit)
        require(key.matches(string(raw["id"])) && key.matches(string(raw["pubkey"])) && Regex("^[0-9a-f]{128}$").matches(string(raw["sig"])))
        number(raw["kind"]); number(raw["created_at"])
        require(string(raw["content"]).length <= limit)
        val tags = raw["tags"] as? JsonArray ?: error("Invalid node tags")
        require(tags.size <= 16)
        for (tag in tags) {
            val values = tag as? JsonArray ?: error("Invalid node tag")
            require(values.size in 1..4 && values.all { string(it).length <= 512 })
        }
        return NostrEvent.fromJson(raw).also { require(Events.verify(it)) }
    }
    fun tag(event: NostrEvent, name: String): String {
        val found = event.tags.filter { it.firstOrNull() == name }
        require(found.size == 1 && found[0].size == 2); return found[0][1]
    }
    fun origin(raw: String): String {
        require(raw.length <= 253)
        val uri = URI(raw); val host = requireNotNull(uri.host)
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()
            && raw == "https://$host" && host.contains('.') && !Regex("^[0-9.]+$").matches(host) && !host.contains(':')
            && !Regex("\\.(localhost|local|internal|home|invalid)$").containsMatchIn(host)
            && host.split('.').all { Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$").matches(it) })
        return raw
    }
    fun host(node: NodeRoomRecord): String = URI(node.origin).host
    fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unbase64(raw: String): ByteArray {
        require(Regex("^[A-Za-z0-9_-]+$").matches(raw))
        return Base64.getUrlDecoder().decode(raw).also { require(base64(it) == raw) }
    }
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

/** Fold node-room/1. Readers always recompute signatures and context. */
object NodeRooms {
    private fun checkedPolicy(raw: JsonElement): JsonObject {
        val p = NodeWire.shape(raw, setOf("tier"), setOf("admitted", "agents", "members", "quiet"))
        val tier = NodeWire.string(p["tier"]); require(tier in setOf("open", "ken", "kith", "kin"))
        fun keys(name: String): List<String>? = p[name]?.let { value ->
            val list = value as? JsonArray ?: error("Invalid policy keys")
            require(list.size <= 256 && (name != "members" || list.isNotEmpty()))
            list.map { NodeWire.string(it).lowercase().also { key -> require(NodeWire.key.matches(key)) } }
        }
        val admitted = keys("admitted"); val members = keys("members")?.distinct()
        val agents = p["agents"]?.let { NodeWire.string(it).also { value -> require(value == "owned-by-members") } }
        val quiet = p["quiet"]?.let { NodeWire.boolean(it).also { value -> require(value && members != null) } } ?: false
        return buildJsonObject {
            put("tier", tier)
            admitted?.let { put("admitted", JsonArray(it.map(::JsonPrimitive))) }
            agents?.let { put("agents", it) }
            members?.let { put("members", JsonArray(it.map(::JsonPrimitive))) }
            if (quiet) put("quiet", true)
        }
    }
    fun canonicalOrigin(raw: String): String = NodeWire.origin(raw)
    fun readTurnPorts(raw: JsonElement?): NodeTurnPorts? = runCatching {
        if (raw == null) NodeTurnPorts.DEFAULT else {
            val value = NodeWire.shape(raw, setOf("udpTcp", "tls"))
            val udpTcp = NodeWire.number(value["udpTcp"]); val tls = NodeWire.number(value["tls"])
            require(udpTcp in 1024..65535 && tls in 1024..65535)
            NodeTurnPorts(udpTcp.toInt(), tls.toInt())
        }
    }.getOrNull()
    fun read(raw: JsonElement?, now: Long, authority: String? = null): NodeRoomRecord? = runCatching {
        require(NodeWire.integer(now)); val event = NodeWire.event(raw, 4096)
        require(event.kind == NODE_RECORD_KIND && event.createdAt <= now + 30 && (authority == null || event.pubkey == authority))
        val body = NodeWire.json(event.content, setOf("v", "profile", "room", "node", "service", "calls", "files"), setOf("ends", "turnPorts", "destruct", "policy"))
        val room = NodeWire.string(body["room"]); val service = NodeWire.string(body["service"])
        val existing = NodeWire.string(body["profile"]) == NODE_ROOM_LABELS[4]
        if (existing) require(NodeWire.string(body["files"]) == "client" && body["destruct"] != null)
        else require(NodeWire.string(body["profile"]) == NODE_ROOM_LABELS[0] && NodeWire.string(body["files"]) == "off" && "destruct" !in body && "policy" !in body)
        val policy = if (existing) NodeExistingRoomPolicy(NodeWire.boolean(body["destruct"]), body["policy"]?.let(::checkedPolicy)) else null
        require(NodeWire.number(body["v"]) == 1L
            && NodeWire.string(body["calls"]) == "relay-only"
            && NodeWire.key.matches(room) && NodeWire.tag(event, "d") == room && NodeWire.key.matches(service) && service != event.pubkey)
        val origin = NodeWire.origin(NodeWire.string(body["node"])); val host = URI(origin).host
        val ends = body["ends"]?.let { NodeWire.number(it) }
        if (ends != null) require(ends > now && ends <= event.createdAt + NODE_HORIZON_SECONDS && NodeWire.tag(event, "expiration") == ends.toString() && event.tags.size == 2)
        else require(event.tags.size == 1)
        val ports = requireNotNull(readTurnPorts(body["turnPorts"]))
        NodeRoomRecord(event, raw as JsonObject, room, event.pubkey, origin, service, ends, "wss://$host/events", listOf("stun:$host:${ports.udpTcp}"),
            listOf("turn:$host:${ports.udpTcp}?transport=udp", "turn:$host:${ports.udpTcp}?transport=tcp", "turns:$host:${ports.tls}?transport=tcp", "turns:turn.$host:443?transport=tcp"), ports, policy)
    }.getOrNull()

    internal fun record(node: NodeRoomRecord, now: Long) = requireNotNull(read(node.wire, now, node.authority))
    fun sign(authoritySk: ByteArray, room: String, origin: String, serviceKey: String, now: Long, endsAt: Long? = null,
        random: () -> ByteArray = { Entropy.bytes(32) }, turnPorts: NodeTurnPorts = NodeTurnPorts.DEFAULT, existing: NodeExistingRoomPolicy? = null): NostrEvent {
        require(NodeWire.integer(now) && NodeWire.key.matches(room) && NodeWire.key.matches(serviceKey) && serviceKey != Schnorr.publicKeyHex(authoritySk)); NodeWire.origin(origin)
        require(endsAt == null || NodeWire.integer(endsAt) && endsAt > now && endsAt <= now + NODE_HORIZON_SECONDS)
        val body = buildJsonObject { put("v", 1); put("profile", if (existing == null) NODE_ROOM_LABELS[0] else NODE_ROOM_LABELS[4]); put("room", room); put("node", origin); put("service", serviceKey)
            put("calls", "relay-only"); put("files", if (existing == null) "off" else "client"); existing?.let { put("destruct", it.destruct); it.policy?.let { policy -> put("policy", checkedPolicy(policy)) } }; if (endsAt != null) put("ends", endsAt)
            if (turnPorts != NodeTurnPorts.DEFAULT) put("turnPorts", turnPorts.toJson()) }
        return Events.sign(authoritySk, NODE_RECORD_KIND, now, listOf(listOf("d", room)) + (endsAt?.let { listOf(listOf("expiration", it.toString())) } ?: emptyList()), body.toString(), random())
    }
    fun parseLink(link: String, now: Long): NodeRoomRecord? = runCatching {
        val fragment = requireNotNull(URI(link).rawFragment); require(fragment.length in 1..8192)
        val body = NodeWire.json(NodeWire.utf8(NodeWire.unbase64(fragment)), setOf("v", "node")); require(NodeWire.number(body["v"]) == NODE_LINK_VERSION.toLong())
        read(body["node"], now)
    }.getOrNull()
    fun encodeLink(base: String, node: JsonObject, now: Long): String {
        requireNotNull(read(node, now)); val uri = URI(base); require(uri.scheme == "https" && !uri.isOpaque && uri.rawAuthority != null && uri.rawFragment == null)
        val canonical = "https://${uri.rawAuthority}${uri.rawPath.takeIf { !it.isNullOrEmpty() } ?: "/"}${uri.rawQuery?.let { "?$it" } ?: ""}"
        return canonical + "#" + NodeWire.base64(buildJsonObject { put("v", NODE_LINK_VERSION); put("node", node) }.toString().toByteArray(Charsets.UTF_8))
    }
    fun expiration(tags: List<List<String>>, now: Long, endsAt: Long? = null): List<List<String>> {
        require(NodeWire.integer(now) && now > 0 && (endsAt == null || NodeWire.integer(endsAt) && endsAt > now))
        val existing = tags.filter { it.firstOrNull() == "expiration" }.map { require(it.size == 2); requireNotNull(it[1].toLongOrNull()).also { n -> require(NodeWire.integer(n) && n.toString() == it[1]) } }
        require(existing.size <= 1)
        val deadline = minOf(now + NODE_HORIZON_SECONDS, endsAt ?: Long.MAX_VALUE, existing.firstOrNull() ?: Long.MAX_VALUE)
        return tags.filter { it.firstOrNull() != "expiration" } + listOf(listOf("expiration", deadline.toString()))
    }
    fun signClosure(node: NodeRoomRecord, authoritySk: ByteArray, operation: String, now: Long, random: () -> ByteArray = { Entropy.bytes(32) }): NostrEvent {
        val pinned = record(node, now); require(Schnorr.publicKeyHex(authoritySk) == pinned.authority && NodeWire.operation.matches(operation))
        return Events.sign(authoritySk, NODE_CLOSE_KIND, now, listOf(listOf("t", "closed"), listOf("d", pinned.room), listOf("server", NodeWire.host(pinned)),
            listOf("operation", operation), listOf("expiration", (now + NODE_HORIZON_SECONDS).toString())), "", random())
    }
    fun readClosure(raw: JsonElement?, node: NodeRoomRecord, now: Long): CompactNodeClosure? = runCatching {
        val pinned = record(node, minOf(now, node.endsAt?.minus(1) ?: now)); val event = NodeWire.event(raw, 4096)
        val operation = NodeWire.tag(event, "operation"); val expiration = NodeWire.tag(event, "expiration")
        require(event.kind == NODE_CLOSE_KIND && event.pubkey == pinned.authority && event.content.isEmpty() && event.createdAt <= now + 30
            && NodeWire.operation.matches(operation) && expiration == (event.createdAt + NODE_HORIZON_SECONDS).toString() && event.createdAt + NODE_HORIZON_SECONDS > now
            && event.tags == listOf(listOf("t", "closed"), listOf("d", pinned.room), listOf("server", NodeWire.host(pinned)), listOf("operation", operation), listOf("expiration", expiration)))
        CompactNodeClosure(event.id, event.sig, event.createdAt, operation, expiration)
    }.getOrNull()
    fun recoverClosure(raw: JsonElement?, node: NodeRoomRecord, now: Long): NostrEvent? = runCatching {
        val proof = NodeWire.shape(raw, setOf("id", "sig", "createdAt", "operation", "expiration"))
        val event = NostrEvent(NODE_CLOSE_KIND, NodeWire.number(proof["createdAt"]), listOf(listOf("t", "closed"), listOf("d", node.room),
            listOf("server", NodeWire.host(node)), listOf("operation", NodeWire.string(proof["operation"])), listOf("expiration", NodeWire.string(proof["expiration"]))),
            "", node.authority, NodeWire.string(proof["id"]), NodeWire.string(proof["sig"]))
        requireNotNull(readClosure(event.toJson(), node, now)); event
    }.getOrNull()
    fun signRetirement(node: NodeRoomRecord, authoritySk: ByteArray, roomSecret: ByteArray, operation: String, now: Long,
        random: () -> ByteArray = { Entropy.bytes(32) }): NostrEvent {
        val pinned = record(node, now); val room = deriveRoom(roomSecret)
        try {
            require(room.roomId == pinned.room && Schnorr.publicKeyHex(authoritySk) == pinned.authority && NodeWire.operation.matches(operation))
            val body = buildJsonObject { put("v", 1); put("profile", NODE_ROOM_LABELS[3]); put("descriptor", pinned.event.id); put("operation", operation); put("closed", true) }
            return Events.sign(authoritySk, NODE_RETIREMENT_KIND, now, expiration(listOf(listOf("d", pinned.room)), now, pinned.endsAt), Nip44.encrypt(body.toString(), room.roomKey, random()), random())
        } finally { room.roomKey.fill(0) }
    }
    fun readRetirement(raw: JsonElement?, node: NodeRoomRecord, roomSecret: ByteArray, now: Long): Pair<String, Long>? {
        var key: ByteArray? = null
        return runCatching {
            val pinned = record(node, now); val room = deriveRoom(roomSecret); key = room.roomKey; val event = NodeWire.event(raw, 4096)
            require(room.roomId == pinned.room && event.kind == NODE_RETIREMENT_KIND && event.pubkey == pinned.authority && event.createdAt <= now + 30 && event.tags.size == 2
                && NodeWire.tag(event, "d") == pinned.room && NodeWire.tag(event, "expiration") == minOf(event.createdAt + NODE_HORIZON_SECONDS, pinned.endsAt ?: Long.MAX_VALUE).toString()
                && NodeWire.tag(event, "expiration").toLong() > now)
            val body = NodeWire.json(Nip44.decrypt(event.content, room.roomKey), setOf("v", "profile", "descriptor", "operation", "closed"))
            val operation = NodeWire.string(body["operation"])
            require(NodeWire.number(body["v"]) == 1L && NodeWire.string(body["profile"]) == NODE_ROOM_LABELS[3] && NodeWire.string(body["descriptor"]) == pinned.event.id
                && NodeWire.boolean(body["closed"]) && NodeWire.operation.matches(operation))
            operation to event.createdAt
        }.getOrNull().also { key?.fill(0) }
    }
    /** Completion evidence only, never local deletion authority. */
    fun receipt(raw: JsonElement?, serviceKey: String, operation: String, now: Long): NodeCloseReceipt? = runCatching {
        val event = NodeWire.event(raw, 8192)
        require(NodeWire.integer(now) && NodeWire.key.matches(serviceKey) && NodeWire.operation.matches(operation) && event.kind == NODE_RECEIPT_KIND && event.pubkey == serviceKey
            && event.createdAt <= now + 30 && event.tags.size == 2 && NodeWire.tag(event, "operation") == operation
            && NodeWire.tag(event, "expiration") == (event.createdAt + NODE_HORIZON_SECONDS).toString() && event.createdAt + NODE_HORIZON_SECONDS > now)
        val body = NodeWire.json(event.content, setOf("v", "operation", "published", "callsRevoked", "relayPurged", "eventsDeleted", "completedAt"))
        require(NodeWire.number(body["v"]) == 1L && NodeWire.string(body["operation"]) == operation && NodeWire.boolean(body["callsRevoked"])
            && NodeWire.boolean(body["relayPurged"]) && NodeWire.number(body["completedAt"]) == event.createdAt)
        NodeCloseReceipt(operation, NodeWire.boolean(body["published"]), eventsDeleted = NodeWire.number(body["eventsDeleted"]), completedAt = event.createdAt)
    }.getOrNull()
    fun verifyGrant(raw: JsonElement?, node: NodeRoomRecord, device: String, now: Long): Boolean = runCatching {
        val pinned = record(node, now); val event = NodeWire.event(raw, 16384)
        require(event.kind == GROUP_GRANT_KIND && event.pubkey == pinned.authority && event.content.isEmpty() && event.createdAt <= now + 30 && event.tags.size == 10 && event.tags.all { it.size == 2 })
        require(NodeWire.tag(event, "t") == "grant" && NodeWire.operation.matches(NodeWire.tag(event, "grant")) && NodeWire.tag(event, "d") == pinned.room
            && NodeWire.tag(event, "device") == device && device != pinned.authority && device != pinned.serviceKey && NodeWire.tag(event, "server") == NodeWire.host(pinned) && NodeWire.tag(event, "status") == "active")
        require(event.tags.filter { it[0] == "scope" }.map { it[1] }.sorted() == listOf("relay-read", "relay-write", "turn"))
        val expiration = NodeWire.tag(event, "expiration").toLong(); require(NodeWire.integer(expiration) && expiration.toString() == NodeWire.tag(event, "expiration") && expiration > now && expiration <= event.createdAt + MAX_GRANT_SECONDS)
        require(event.tags.all { it[0] in setOf("t", "grant", "d", "device", "server", "status", "expiration", "scope") }); true
    }.getOrDefault(false)
}
