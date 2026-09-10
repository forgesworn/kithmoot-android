package dev.forgesworn.kithmoot.discovery

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A bounded read-only actor. Only actual EOSE from every configured relay
 * completes history. All consumer callbacks share this actor's ordering;
 * disconnect, timeout and overflow discard queued frames and restart reads. */
class BoxRelayReader(
    urls: List<String>, private val sockets: RelaySocketFactory, parent: CoroutineScope,
    private val unavailable: () -> Unit, private val nowMs: () -> Long = { System.currentTimeMillis() },
) : BoxReader {
    private val urls = urls.distinct().also { require(it.isNotEmpty() && it.size <= 8); it.forEach { u -> require(dev.forgesworn.kithmoot.protocol.LinkCards.isRelayUrl(u)) } }
    private data class Request(val filter: Filter, val receive: (NostrEvent) -> Unit, val ready: () -> Unit, val eosed: MutableSet<String> = mutableSetOf(), var deadline: Job? = null)
    private data class Link(var socket: RelaySocket? = null, var open: Boolean = false)
    private sealed interface Command {
        data class Add(val id: String, val request: Request) : Command
        data class Remove(val id: String) : Command
        data class Retry(val generation: Long) : Command
        data class Open(val generation: Long, val url: String) : Command
        data class Frame(val generation: Long, val url: String, val text: String) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val incoming = Channel<Command.Frame>(64)
    private val history = AtomicBoolean(false)
    private val failure = Channel<Unit>(Channel.CONFLATED)
    private val stopped = AtomicBoolean(false)
    private val rejecting = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val requests = linkedMapOf<String, Request>()
    private val links = linkedMapOf<String, Link>()
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var connectionDeadline: Job? = null
    private var retry: Job? = null
    private var window = nowMs()
    private var frames = 0
    private val actor = scope.launch {
        try {
            while (isActive) select<Unit> {
                failure.onReceive { failed() }
                incoming.onReceive { command -> if (command.generation == generation.get() && !rejecting.get()) frame(command) }
                commands.onReceive { command ->
                    when (command) {
                        is Command.Retry -> if (command.generation == generation.get()) { retry = null; connect() }
                        is Command.Add -> {
                            history.set(false)
                            if (requests.size >= 3) { failed(); return@onReceive }
                            requests[command.id] = command.request
                            if (links.isEmpty() && retry == null) connect()
                            else for ((_, link) in links) if (link.open) send(link, command.id, command.request)
                        }
                        is Command.Remove -> {
                            requests.remove(command.id)?.deadline?.cancel()
                            for ((_, link) in links) if (link.open) link.socket?.send(RelayCodec.closeFrame(command.id))
                        }
                        is Command.Open -> if (command.generation == generation.get() && !rejecting.get()) {
                            val link = links[command.url] ?: return@onReceive
                            link.open = true
                            for ((id, request) in requests) send(link, id, request)
                            if (links.values.all { it.open }) connectionDeadline?.cancel()
                        }
                        is Command.Frame -> if (command.generation == generation.get() && !rejecting.get()) frame(command)
                    }
                }
            }
        } finally { reset(); commands.close(); incoming.close(); failure.close() }
    }
    private fun enqueue(command: Command) {
        if (!stopped.get() && !commands.trySend(command).isSuccess) fail()
    }
    private fun fail() {
        if (stopped.get()) return
        rejecting.set(true); history.set(false)
        failure.trySend(Unit)
    }
    override fun subscribe(filter: Filter, receive: (NostrEvent) -> Unit, ready: () -> Unit): () -> Unit {
        check(!stopped.get())
        val id = "box-${sequence.incrementAndGet()}"
        enqueue(Command.Add(id, Request(filter, receive, ready)))
        return { enqueue(Command.Remove(id)) }
    }
    private fun connect() {
        if (stopped.get() || requests.isEmpty()) return
        rejecting.set(false)
        val gen = generation.get()
        window = nowMs(); frames = 0
        connectionDeadline = scope.launch { delay(15_000); if (gen == generation.get()) fail() }
        for (url in urls) {
            val link = Link(); links[url] = link
            try {
                link.socket = sockets.open(url, object : RelaySocketListener {
                    override fun onOpen() { enqueue(Command.Open(gen, url)) }
                    override fun onClosed(reason: String) { if (gen == generation.get()) fail() }
                    override fun onMessage(text: String) {
                        if (gen != generation.get() || rejecting.get() || stopped.get()) return
                        if (text.toByteArray(Charsets.UTF_8).size > 33_000) { fail(); return }
                        if (!incoming.trySend(Command.Frame(gen, url, text)).isSuccess) fail()
                    }
                })
            } catch (_: Exception) { fail() }
        }
    }
    private fun send(link: Link, id: String, request: Request) {
        try { link.socket?.send(RelayCodec.requestFrame(id, listOf(request.filter))) } catch (_: Exception) { fail(); return }
        if (request.deadline == null) {
            val gen = generation.get()
            request.deadline = scope.launch { delay(15_000); if (gen == generation.get()) fail() }
        }
    }
    private fun frame(command: Command.Frame) {
        if (nowMs() - window >= 10_000) { window = nowMs(); frames = 0 }
        if (++frames > 256) { fail(); return }
        if (!boundedDepth(command.text)) { fail(); return }
        val raw = try { Json.parseToJsonElement(command.text) as? JsonArray } catch (_: Exception) { null } ?: return
        val id = (raw.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val request = requests[id] ?: return
        when ((raw.firstOrNull() as? JsonPrimitive)?.content) {
            "EOSE" -> if (raw.size == 2) {
                val already = request.eosed.containsAll(urls)
                request.eosed += command.url
                if (!already && request.eosed.containsAll(urls)) { request.deadline?.cancel(); request.deadline = null; history.set(requests.values.all { it.eosed.containsAll(urls) }); request.ready() }
            }
            "CLOSED" -> {
                // Full ids name immutable data. A relay may finish that
                // lookup after its real EOSE; live histories and incomplete
                // lookups must still invalidate on closure.
                val ids = request.filter.ids
                val immutable = !ids.isNullOrEmpty() && ids.all { id -> id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' } }
                if (!immutable || command.url !in request.eosed) fail()
            }
            "EVENT" -> if (raw.size == 3) {
                val event = try { NostrEvent.fromJson(raw[2]) } catch (_: Exception) { return }
                val f = request.filter
                if (f.ids?.contains(event.id) == false || f.authors?.contains(event.pubkey) == false || f.kinds?.contains(event.kind) == false) return
                if (f.tags.any { (key, values) -> event.tags.none { it.size >= 2 && it[0] == key.removePrefix("#") && it[1] in values } }) return
                request.receive(event)
            }
        }
    }
    /** Bound parser recursion before giving network text to the JSON parser. */
    private fun boundedDepth(text: String): Boolean {
        var quoted = false; var escaped = false; var depth = 0
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[', '{' -> { if (++depth > 16) return false }
                ']', '}' -> { if (--depth < 0) return false }
            }
        }
        return depth == 0 && !quoted
    }
    private fun reset() {
        generation.incrementAndGet(); history.set(false)
        connectionDeadline?.cancel(); connectionDeadline = null
        for (request in requests.values) { request.deadline?.cancel(); request.deadline = null; request.eosed.clear() }
        for (link in links.values) runCatching { link.socket?.close() }
        links.clear()
    }
    private fun failed() {
        if (retry != null || stopped.get()) return
        reset(); unavailable()
        val gen = generation.get()
        retry = scope.launch { delay(5_000); enqueue(Command.Retry(gen)) }
    }
    override fun trustedHistory(): Boolean = history.get() && !rejecting.get() && !stopped.get()
    override fun close() { if (stopped.compareAndSet(false, true)) { history.set(false); scope.cancel(); actor.cancel() } }
}
