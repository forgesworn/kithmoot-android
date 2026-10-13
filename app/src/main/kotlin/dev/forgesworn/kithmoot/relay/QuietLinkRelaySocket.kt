package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.*

/** Two streams on one consented peer. Circle traffic keeps /events; gift
 * wraps use /drops, whose broadcast policy deliberately has no recipient
 * index. Original filters are applied locally before the pool sees a wrap. */
internal class QuietLinkRelaySocket(
    private val canonical: String,
    private val route: String,
    private val factory: LinkRelaySocketFactory,
    private val consent: ActiveLinkRoute,
    private val listener: RelaySocketListener,
) : RelaySocket {
    private enum class Leg { EVENTS, DROPS }
    private data class Request(val id: String, val filters: Map<Leg, List<JsonObject>>,
        val wires: Map<Leg, String>, val complete: MutableSet<Leg> = mutableSetOf())
    private val lock = Any()
    private val sockets = mutableMapOf<Leg, RelaySocket>()
    private val opened = mutableSetOf<Leg>()
    private val requests = mutableMapOf<String, Request>()
    private val wires = mutableMapOf<String, Pair<Request, Leg>>()
    private val waiting = mutableListOf<Pair<Leg, String>>()
    private var waitingBytes = 0
    private var serial = 0L
    private var starting = true
    private var ready = false
    private var closed = false

    fun start(): QuietLinkRelaySocket {
        try {
            for (leg in Leg.entries) {
                checkAllowed()
                if (synchronized(lock) { closed }) break
                val url = if (leg == Leg.EVENTS) canonical else canonical.removeSuffix("/events") + "/drops"
                val child = factory.open(url, route, object : RelaySocketListener {
                    override fun onOpen() { synchronized(lock) { opened += leg }; announceReady() }
                    override fun onMessage(text: String) = receive(leg, text)
                    override fun onClosed(reason: String) { shutdown("Link quiet relay connection closed") }
                })
                val dispose = synchronized(lock) { if (closed) true else { sockets[leg] = child; false } }
                if (dispose) child.close()
            }
            synchronized(lock) { starting = false }
            announceReady()
            return this
        } catch (failure: Exception) {
            shutdown("Link quiet relay could not open")
            throw failure
        }
    }

    private fun checkAllowed() {
        val active = try { consent.routeId(canonical) } catch (failure: Exception) {
            shutdown("Link quiet relay consent unavailable")
            throw IllegalStateException("Link relay consent could not be checked", failure)
        }
        if (active != route) {
            shutdown("Link quiet relay consent changed")
            throw IllegalStateException("This Link relay no longer has its original consent")
        }
    }

    private fun announceReady() {
        val queued = synchronized(lock) {
            if (closed || ready || starting || opened.size != 2 || sockets.size != 2) return
            ready = true
            waiting.toList().also { waiting.clear(); waitingBytes = 0 }
        }
        listener.onOpen()
        queued.forEach { (leg, text) -> receive(leg, text) }
    }

    override fun send(text: String) {
        checkAllowed()
        require(text.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Link quiet relay frame exceeds its bound" }
        val frame = Json.parseToJsonElement(text).jsonArray
        val actions = synchronized(lock) {
            check(ready && !closed) { "Link quiet relay is not open" }
            when (frame.firstOrNull()?.jsonPrimitive?.content) {
                "REQ" -> request(frame)
                "CLOSE" -> remove(frame[1].jsonPrimitive.content)
                "EVENT" -> {
                    val kind = frame[1].jsonObject.getValue("kind").jsonPrimitive.int
                    listOf((if (kind == 1059) Leg.DROPS else Leg.EVENTS) to text)
                }
                else -> listOf(Leg.EVENTS to text) // NIP-42 and circle reconciliation.
            }.map { (leg, message) -> checkNotNull(sockets[leg]) to message }
        }
        try { actions.forEach { (socket, message) -> checkAllowed(); socket.send(message) } }
        catch (failure: Exception) { shutdown("Link quiet relay send failed"); throw failure }
    }

    private fun request(frame: JsonArray): List<Pair<Leg, String>> {
        require(frame.size >= 3)
        val id = frame[1].jsonPrimitive.content
        require(id.length in 1..128)
        require(requests.size < 64 || id in requests) { "Too many Link quiet relay subscriptions" }
        val filters = mutableMapOf<Leg, MutableList<JsonObject>>()
        for (value in frame.drop(2)) {
            val filter = value.jsonObject
            require(filter.keys.all { it in setOf("ids", "authors", "kinds", "since", "until", "limit") ||
                (it.length == 2 && it.startsWith("#")) }) { "Unsupported Link quiet relay filter" }
            val kinds = filter["kinds"]?.jsonArray?.map { it.jsonPrimitive.int }
            for ((key, value) in filter) {
                when (key) {
                    "since", "until" -> value.jsonPrimitive.long
                    "limit" -> require(value.jsonPrimitive.int >= 0)
                    "kinds" -> Unit // Parsed above before subscription replacement.
                    else -> value.jsonArray.forEach { require(it is JsonPrimitive && it.isString) }
                }
            }
            if (kinds == null || 1059 in kinds) {
                filters.getOrPut(Leg.DROPS) { mutableListOf() } += JsonObject(filter +
                    ("kinds" to JsonArray(listOf(JsonPrimitive(1059)))))
            }
            if (kinds == null || kinds.any { it != 1059 }) {
                filters.getOrPut(Leg.EVENTS) { mutableListOf() } += if (kinds == null) filter else
                    JsonObject(filter + ("kinds" to JsonArray(kinds.filter { it != 1059 }.map(::JsonPrimitive))))
            }
        }
        require(filters.isNotEmpty()) { "Link quiet relay filter selects no kinds" }
        val actions = remove(id).toMutableList()
        val mapping = filters.keys.associateWith { "link-${++serial}-${it.name.lowercase()}" }
        val request = Request(id, filters, mapping)
        requests[id] = request
        mapping.forEach { (leg, wire) ->
            wires[wire] = request to leg
            val outward = filters.getValue(leg).map { filter -> if (leg == Leg.EVENTS) filter else
                JsonObject(filter.filterKeys { it != "authors" && !it.startsWith("#") }) }
            actions += leg to JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(wire)) + outward).toString()
        }
        return actions
    }

    private fun remove(id: String): List<Pair<Leg, String>> = requests.remove(id)?.wires?.map { (leg, wire) ->
        wires.remove(wire)
        leg to JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(wire))).toString()
    }.orEmpty()

    private fun receive(leg: Leg, text: String) {
        if (synchronized(lock) { closed }) return
        try { checkAllowed() } catch (_: Exception) { return }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > 1_048_576) { shutdown("Link quiet relay frame exceeded its bound"); return }
        val buffered = synchronized(lock) {
            if (closed) return
            if (!ready) {
                if (waiting.size >= 64 || bytes > 1_048_576 - waitingBytes) false else {
                    waiting += leg to text; waitingBytes += bytes; true
                }
            } else null
        }
        if (buffered != null) {
            if (!buffered) shutdown("Link quiet relay opening buffer exceeded its bound")
            return
        }
        val frame = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
        val kind = (frame.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return
        if (leg == Leg.DROPS && kind == "AUTH") {
            shutdown("Link drop endpoint requested unsupported authentication")
            return
        }
        val message = synchronized(lock) {
            if (closed) return
            if (kind !in setOf("EVENT", "EOSE", "CLOSED")) text else {
                val wire = (frame.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return
                val (request, expected) = wires[wire] ?: return
                if (leg != expected) return
                if (kind == "EVENT") {
                    val event = runCatching { NostrEvent.fromJson(frame[2]) }.getOrNull() ?: return
                    if (request.filters.getValue(leg).none { matches(it, event) }) return
                }
                if (kind == "EOSE") {
                    if (!request.complete.add(leg) || request.complete != request.wires.keys) return
                }
                JsonArray(frame.toMutableList().also { it[1] = JsonPrimitive(request.id) }).toString()
            }
        }
        listener.onMessage(message)
    }

    private fun matches(filter: JsonObject, event: NostrEvent): Boolean {
        fun strings(name: String) = filter[name]?.jsonArray?.map { it.jsonPrimitive.content }
        if (strings("ids")?.none { event.id.startsWith(it) } == true) return false
        if (strings("authors")?.none { event.pubkey.startsWith(it) } == true) return false
        if (filter["kinds"]?.jsonArray?.none { it.jsonPrimitive.int == event.kind } == true) return false
        if (filter["since"]?.jsonPrimitive?.long?.let { event.createdAt < it } == true) return false
        if (filter["until"]?.jsonPrimitive?.long?.let { event.createdAt > it } == true) return false
        return filter.keys.filter { it.startsWith("#") }.all { name ->
            val values = checkNotNull(strings(name))
            event.tags.any { it.size >= 2 && it[0] == name.substring(1) && it[1] in values }
        }
    }

    override fun close() = shutdown("Link quiet relay closed")

    private fun shutdown(reason: String) {
        val children = synchronized(lock) {
            if (closed) return
            closed = true
            requests.clear(); wires.clear(); waiting.clear(); waitingBytes = 0
            sockets.values.toList().also { sockets.clear() }
        }
        children.forEach { runCatching { it.close() } }
        listener.onClosed(reason)
    }
}
