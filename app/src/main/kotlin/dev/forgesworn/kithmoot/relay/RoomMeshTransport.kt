package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Byte boundary supplied by BLE (or a test link). Never an internet fallback.
 * Calls must not invoke or wait for receive callbacks inline. resetQueued may
 * suspend while the platform stops its radio; other calls are non-blocking.
 * resetQueued discards every previously offered frame before returning; subsequent
 * offers may reconnect. A platform unable to provide that barrier must fail closed.
 * from/to are routing hints, not authenticated participants or delivery receipts. */
interface RoomMeshLink : AutoCloseable {
    fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable
    fun offer(bytes: ByteArray, to: String? = null)
    suspend fun resetQueued()
    fun reachable(): Boolean
}

/** JSON-only profile of mesh-webrtc-lan's length-prefixed MeshFrame codec.
 * No binary sidecars are used by the room event/query profile. */
internal object RoomMeshWire {
    const val EVENT = "kithmoot-lab/event/v1"
    const val QUERY = "kithmoot-lab/query/v1"
    const val MAX_BYTES = 20 * 1024

    fun encode(kind: String, payload: JsonObject): ByteArray {
        val json = buildJsonObject { put("k", kind); put("p", payload) }.toString().toByteArray(Charsets.UTF_8)
        require(json.size + 4 <= MAX_BYTES) { "Mesh frame exceeds room profile limit" }
        return ByteBuffer.allocate(json.size + 4).putInt(json.size).put(json).array()
    }

    fun decode(bytes: ByteArray): Pair<String, JsonObject>? = try {
        require(bytes.size in 6..MAX_BYTES)
        val input = ByteBuffer.wrap(bytes)
        require(input.int == bytes.size - 4) // Reject sidecars, trailing bytes and unsigned overflow.
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(input).toString()
        // Bound parser nesting before constructing attacker-controlled JSON trees.
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) }
                '}', ']' -> depth--
            }
        }
        val frame = Json.parseToJsonElement(text).jsonObject
        val kind = frame.getValue("k").jsonPrimitive
        require(kind.isString)
        kind.content to frame.getValue("p").jsonObject
    } catch (_: Exception) { null }
}

/** A bounded, volatile room lane. It never claims complete mesh history or peer
 * delivery. RoomSession still authenticates room membership and decrypts events.
 * It does not forward received traffic to another lane or start a radio itself. */
class RoomMeshTransport(
    private val meshScope: String,
    private val link: RoomMeshLink,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : RoomTransport, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var blocked = false
    private var resetReady = true
    private var generation = 0L
    private var highTime = 0L
    private var queryWindow = -1L
    private var queryCount = 0
    private var replyCount = 0
    private data class Seen(val offers: Int, val at: Long)
    private val seen = linkedMapOf<String, Seen>()
    private val inboundSeen = linkedMapOf<String, Seen>()
    private data class Stored(val event: NostrEvent, val expires: Long, val inbound: Boolean)
    private val retained = linkedMapOf<String, Stored>()
    private data class Reader(val filters: List<Filter>, val inboundOnly: Boolean,
        val event: (NostrEvent) -> Unit, val end: () -> Unit)
    private val readers = linkedSetOf<Reader>()
    private val receiver: AutoCloseable

    init {
        require(Regex("[0-9a-f]{64}").matches(meshScope)) { "An explicit mesh scope is required" }
        receiver = link.subscribe(::receive)
    }

    private fun time(): Long { highTime = maxOf(highTime, nowSeconds()); return highTime }
    private fun prune(at: Long) { retained.entries.removeAll { it.value.expires <= at } }
    private fun checked(event: NostrEvent, at: Long): NostrEvent? {
        if (event.toCompactJson().toByteArray(Charsets.UTF_8).size > 16 * 1024 ||
            event.kind !in 0..65535 || event.createdAt < 0 || event.createdAt > at + 300 || !Events.verify(event)) return null
        if (event.tags.filter { it.firstOrNull() == "expiration" }.any {
                val value = it.getOrNull(1)
                value == null || !value.all { digit -> digit in '0'..'9' } || (value.toLongOrNull() ?: -1) <= at
            }) return null
        // Callers and subscribers must not retain mutable references to cached tags.
        return event.copy(tags = event.tags.map { it.toList() })
    }

    private fun recordObservation(cache: MutableMap<String, Seen>, event: NostrEvent, at: Long): Boolean {
        val previous = cache[event.id]
        if (previous != null && (event.kind !in setOf(20466, 20467, 20468, 20469) ||
                previous.offers >= 3 || at <= previous.at)) return false
        cache[event.id] = Seen((previous?.offers ?: 0) + 1, at)
        while (cache.size > 512) cache.remove(cache.keys.first())
        return true
    }

    private fun accept(event: NostrEvent, at: Long, inbound: Boolean = false) {
        prune(at)
        val deliver = recordObservation(seen, event, at)
        // A local echo may already occupy the ordinary dedup cache. Only an
        // actual receive can establish this separate source observation.
        val deliverInbound = inbound && recordObservation(inboundSeen, event, at)
        if (!deliver && !deliverInbound) return
        if (event.kind in setOf(1460, 1463) && !blocked) {
            val old = retained[event.id]
            val expires = minOf(at + 3600, event.tags.filter { it.firstOrNull() == "expiration" }
                .mapNotNull { it.getOrNull(1)?.toLongOrNull() }.minOrNull() ?: Long.MAX_VALUE,
                old?.expires ?: Long.MAX_VALUE)
            retained[event.id] = Stored(event, expires, inbound || old?.inbound == true)
            while (retained.size > 64) retained.remove(retained.keys.first())
        }
        readers.toList().forEach { reader ->
            if ((if (reader.inboundOnly) deliverInbound else deliver) && reader.filters.any { matches(it, event) })
                reader.event(event.copy(tags = event.tags.map { it.toList() }))
        }
    }

    private fun payload(event: NostrEvent) = buildJsonObject { put("scope", meshScope); put("event", event.toJson()) }

    override fun publish(event: NostrEvent) = synchronized(lock) {
        if (closed || blocked) throw PublicationNotOfferedException("Mesh publication is closed or awaiting rekey")
        offer(event)
    }

    private fun offer(event: NostrEvent) {
        val at = time()
        val safe = requireNotNull(checked(event, at)) { "Invalid mesh room event" }
        link.offer(RoomMeshWire.encode(RoomMeshWire.EVENT, payload(safe)))
        accept(safe, at)
    }

    /** Local admission cannot be reported as either delivery or a refusal. */
    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
        publish(event)
        throw PublicationUnconfirmedException()
    }

    override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean = synchronized(lock) {
        if (!closed && !blocked && this.generation == generation && stillAllowed()) {
            offer(event)
            throw PublicationUnconfirmedException()
        }
        false
    }

    override fun receivedEventConfirmsPublication(eventId: String): Boolean = false

    override fun publicationGeneration(): Long = synchronized(lock) { generation }
    override fun reachable(): Boolean = synchronized(lock) { !closed && !blocked && link.reachable() }

    // queryStored and subscribeReplayed intentionally retain the interface's
    // fail-closed defaults. A timer is not proof of complete mesh history.
    override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> = coroutineScope {
        require(timeoutMs in 1..15_000)
        val found = linkedMapOf<String, NostrEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            subscribe(filters).collect { if (found.size < 64) found[it.id] = it }
        }
        try { delay(timeoutMs); job.cancelAndJoin(); found.values.toList() } finally { job.cancel() }
    }

    /** Actual inbound observations and their bounded replay for an explicitly
     * enabled forwarding owner. Local publication is not source observation.
     * A received copy still cannot prove participant identity or delivery. */
    fun subscribeInbound(filters: List<Filter>): Flow<NostrEvent> = subscribeObservations(filters, inboundOnly = true)

    /** The selected keeper can await actual local inbound-reader registration.
     * No callback on flow construction, remote replay or participant delivery. */
    internal fun subscribeKeeperRequests(filters: List<Filter>, onInstalled: () -> Unit): Flow<NostrEvent> {
        require(filters.isNotEmpty() && filters.all { it.kinds?.let { kinds ->
            kinds.isNotEmpty() && kinds.all { kind -> kind in setOf(20466, 20468) }
        } == true })
        val frozen = parseFilters(JsonArray(filters.map { it.toJson() }))
        return subscribeObservations(frozen, inboundOnly = true, onInstalled = onInstalled)
    }

    internal fun hasScope(scope: String): Boolean = meshScope == scope

    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = subscribeObservations(filters, inboundOnly = false)

    private fun subscribeObservations(filters: List<Filter>, inboundOnly: Boolean,
        onInstalled: () -> Unit = {}): Flow<NostrEvent> = callbackFlow {
        val frozen = parseFilters(JsonArray(filters.map { it.toJson() }))
        // trySend can resume an Unconfined collector inline. Never let room
        // callbacks run while receive/publication holds the transport lock.
        // Preserve a test scheduler when it queues work; immediate dispatchers
        // use Default, and even a Main dispatcher must always enqueue resumes.
        val parentDispatcher = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        val delegate = parentDispatcher?.takeIf { it.isDispatchNeeded(coroutineContext) } ?: Dispatchers.Default
        val queued = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
        }
        val notifications = Channel<NostrEvent>(64)
        val delivery = launch(queued) {
            try {
                for (event in notifications) send(event)
                close()
            } catch (error: Exception) { close(error) }
        }
        val reader = Reader(frozen, inboundOnly, { event ->
            if (!notifications.trySend(event).isSuccess)
                notifications.close(IllegalStateException("Mesh subscription capacity exceeded"))
        }, { notifications.close() })
        try {
            synchronized(lock) {
                check(!closed) { "Mesh transport closed" }
                check(readers.size < 32) { "Too many mesh subscriptions" }
                readers.add(reader)
                prune(time())
                retained.values.filter { row -> (!inboundOnly || row.inbound) && frozen.any { matches(it, row.event) } }.forEach {
                    reader.event(it.event.copy(tags = it.event.tags.map { tag -> tag.toList() }))
                }
                if (!blocked) link.offer(RoomMeshWire.encode(RoomMeshWire.QUERY, buildJsonObject {
                    put("scope", meshScope); put("filters", JsonArray(frozen.map { it.toJson() }))
                }))
            }
            // A callback may resume an owner inline. Never invoke it while
            // holding the mesh monitor; the finally path owns reader cleanup.
            onInstalled()
            awaitClose { }
        } finally {
            synchronized(lock) { readers.remove(reader) }
            notifications.cancel()
            delivery.cancel()
        }
    }.buffer(0) // One bounded mailbox, rather than a second downstream queue.

    private fun receive(bytes: ByteArray, from: String) = synchronized(lock) {
        if (closed || from.isBlank() || from.length > 256) return@synchronized
        val (kind, body) = RoomMeshWire.decode(bytes) ?: return@synchronized
        if (body["scope"] != JsonPrimitive(meshScope)) return@synchronized
        try {
            val at = time()
            when (kind) {
                RoomMeshWire.EVENT -> {
                    val encoded = body.getValue("event").jsonObject
                    val event = NostrEvent.fromJson(encoded)
                    // Parsing helpers are permissive elsewhere; this boundary requires
                    // the actual Nostr JSON types, including numbers and string tags.
                    if (event.toJson().all { (key, value) -> encoded[key] == value })
                        checked(event, at)?.let { accept(it, at, inbound = true) }
                }
                RoomMeshWire.QUERY -> {
                    if (blocked || bytes.size > 4096) return@synchronized
                    if (queryWindow != at) { queryWindow = at; queryCount = 0; replyCount = 0 }
                    if (queryCount++ >= 8 || replyCount >= 8) return@synchronized
                    val filters = parseFilters(body.getValue("filters").jsonArray)
                    prune(at)
                    for (row in retained.values) {
                        if (replyCount >= 8) break
                        if (filters.any { matches(it, row.event) }) {
                            replyCount++
                            link.offer(RoomMeshWire.encode(RoomMeshWire.EVENT, payload(row.event)), from)
                        }
                    }
                }
            }
        } catch (_: Exception) { /* Malformed input and failed best-effort replay have no authority. */ }
    }

    override suspend fun beginRekey() {
        val resetGeneration = synchronized(lock) {
            check(!closed)
            blocked = true; resetReady = false; generation++
            retained.clear(); seen.clear(); inboundSeen.clear()
            generation
        }
        // Never hold the room lock while waiting for Android's main thread.
        link.resetQueued() // If this fails, publication remains blocked.
        synchronized(lock) {
            if (!closed && generation == resetGeneration) resetReady = true
        }
    }
    override suspend fun rekey(roomKey: ByteArray) = synchronized(lock) {
        check(!closed && blocked)
        retained.clear(); seen.clear(); inboundSeen.clear() // Old-epoch arrivals during the barrier cannot become replay.
    }
    override fun completeRekey() = synchronized(lock) { check(!closed && resetReady); blocked = false }

    override fun publishRecovery(event: NostrEvent) = synchronized(lock) {
        check(!closed && resetReady)
        require(event.kind in setOf(20468, 20469, 20471, 20472)) { "Not an epoch recovery event" }
        offer(event)
    }

    /** Original keeper notices remain available through the chat barrier,
     * after reset discarded old queued frames. Never a peer receipt. */
    internal fun keeperControlReady(): Boolean = synchronized(lock) { !closed && resetReady && link.reachable() }
    internal suspend fun publishKeeperControlGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean): Boolean {
        require(event.kind in setOf(1461, 1462)) { "Not a keeper authority notice" }
        return keeperGuarded(event, generation, stillAllowed)
    }
    internal suspend fun publishKeeperAnswerGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean): Boolean {
        require(event.kind in setOf(20467, 20469)) { "Not a keeper request answer" }
        return keeperGuarded(event, generation, stillAllowed)
    }
    private fun keeperGuarded(event: NostrEvent, generation: Long, stillAllowed: () -> Boolean): Boolean {
        return synchronized(lock) {
            if (closed || !resetReady || this.generation != generation || !stillAllowed() || !link.reachable()) false
            else { offer(event); throw PublicationUnconfirmedException() }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true; blocked = true; generation++
            try { receiver.close() } finally {
                try { link.close() } finally {
                    retained.clear(); seen.clear(); inboundSeen.clear(); readers.toList().forEach { it.end() }; readers.clear()
                }
            }
        }
    }

    companion object {
        private fun parseFilters(json: JsonArray): List<Filter> {
            require(json.size in 1..8 && json.toString().toByteArray(Charsets.UTF_8).size <= 3500)
            return json.map { value ->
                val f = value.jsonObject
                require(f.keys.all { it in setOf("ids", "authors", "kinds", "since", "until", "limit") ||
                    (it.length == 2 && it[0] == '#') })
                fun strings(v: JsonElement): List<String> = v.jsonArray.map {
                    require(it.jsonPrimitive.isString); it.jsonPrimitive.content
                }
                Filter(ids = f["ids"]?.let(::strings), authors = f["authors"]?.let(::strings),
                    kinds = f["kinds"]?.jsonArray?.map { require(!it.jsonPrimitive.isString); it.jsonPrimitive.int },
                    tags = f.filterKeys { it.startsWith('#') }.mapValues { strings(it.value) },
                    since = f["since"]?.jsonPrimitive?.long, until = f["until"]?.jsonPrimitive?.long,
                    limit = f["limit"]?.jsonPrimitive?.int?.also { require(it in 0..64) })
            }
        }
        private fun matches(f: Filter, e: NostrEvent): Boolean =
            (f.ids == null || f.ids.any { e.id.startsWith(it) }) &&
            (f.authors == null || f.authors.any { e.pubkey.startsWith(it) }) &&
            (f.kinds == null || e.kind in f.kinds) &&
            (f.since == null || e.createdAt >= f.since) && (f.until == null || e.createdAt <= f.until) &&
            f.tags.all { (name, values) -> e.tags.any { it.size >= 2 && it[0] == name.drop(1) && it[1] in values } }
    }
}
