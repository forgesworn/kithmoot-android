package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * What a room needs from the outside world: somewhere to put events, and a way
 * to be told about them.
 *
 * [RelayPool] is the only production implementation. The interface exists so the
 * room session - announce-and-respond, roster merge, role arbitration - is
 * testable without a socket anywhere in sight.
 */
interface RoomTransport {
    /** Fire and forget. Delivery is the pool's problem, not the caller's. */
    fun publish(event: NostrEvent)

    /**
     * A cold flow of matching events, de-duplicated across relays. Cancelling
     * the collector closes the subscription on every relay.
     */
    fun subscribe(filters: List<Filter>): Flow<NostrEvent>
}

/**
 * A pool of relays that behaves as one.
 *
 * The design rule is that **no single relay may be load-bearing**. Every
 * publish goes to every connected relay; every subscription is opened on every
 * relay; an event that arrives from three relays is delivered once. A relay can
 * be slow, hostile, rate-limiting or simply down, and the room does not notice
 * so long as one of the others is up.
 *
 * State is guarded by a lock rather than confined to a coroutine because socket
 * callbacks arrive on whatever thread the websocket client feels like using.
 */
class RelayPool(
    private val urls: List<String>,
    private val sockets: RelaySocketFactory,
    private val scope: CoroutineScope,
    private val policy: RelayPolicy = RelayPolicy(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
) : RoomTransport {

    private val lock = Any()
    private val links = mutableMapOf<String, RelayLink>()
    private val subscriptions = linkedMapOf<String, PoolSubscription>()
    private val storedQueries = linkedMapOf<String, StoredQuery>()
    private val publications = linkedMapOf<String, Publication>()
    private val nextSubscriptionId = AtomicLong(0)
    private var started = false

    private val _connected = MutableStateFlow<Set<String>>(emptySet())

    /** Which relays are up right now. The interface shows this; a room with one relay left still works. */
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    /** Every relay we were asked to use, up or not. */
    val relayUrls: List<String> get() = urls

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            for (url in urls) links[url] = RelayLink(url).also { it.job = launchLink(it) }
        }
    }

    fun stop() {
        val closing: List<RelayLink>
        synchronized(lock) {
            if (!started) return
            started = false
            closing = links.values.toList()
            links.clear()
            subscriptions.clear()
            storedQueries.values.forEach { it.result.completeExceptionally(IllegalStateException("Relays stopped")) }
            storedQueries.clear()
            publications.values.forEach { it.result.complete(false) }
            publications.clear()
        }
        for (link in closing) {
            link.job?.cancel()
            runCatching { link.socket?.close() }
        }
        _connected.value = emptySet()
    }

    override fun publish(event: NostrEvent) {
        val frame = RelayCodec.publishFrame(event)
        val targets: List<RelayLink>
        synchronized(lock) { targets = links.values.toList() }
        for (link in targets) link.sendOrQueue(frame)
    }

    /** Confirm storage before exposing a durable link. An OK from any connected relay suffices. */
    suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long = 15_000): Boolean = withTimeout(timeoutMs) {
        connected.first { it.isNotEmpty() }
        val publication: Publication
        val targets: List<RelayLink>
        synchronized(lock) {
            targets = links.values.filter { it.isOpen }
            check(targets.isNotEmpty()) { "No relay is connected" }
            check(event.id !in publications) { "Event publication is already pending" }
            publication = Publication(targets.map { it.url }.toSet())
            publications[event.id] = publication
        }
        try {
            targets.forEach { it.sendIfOpen(RelayCodec.publishFrame(event)) }
            publication.result.await()
        } finally { synchronized(lock) { publications.remove(event.id) } }
    }

    /** A complete snapshot from the currently connected relays. Disconnection, CLOSED,
     * overflow or missing EOSE fails the query; partial events never become admission. */
    suspend fun queryStored(filters: List<Filter>, timeoutMs: Long = 15_000): List<NostrEvent> = withTimeout(timeoutMs) {
        connected.first { it.isNotEmpty() }
        val id = "km-stored-${nextSubscriptionId.incrementAndGet()}"
        val query: StoredQuery
        val targets: List<RelayLink>
        synchronized(lock) {
            targets = links.values.filter { it.isOpen }
            check(targets.isNotEmpty()) { "No relay is connected" }
            query = StoredQuery(targets.map { it.url }.toSet())
            storedQueries[id] = query
        }
        try {
            targets.forEach { it.sendIfOpen(RelayCodec.requestFrame(id, filters)) }
            query.result.await()
        } finally {
            synchronized(lock) { storedQueries.remove(id) }
            targets.forEach { it.sendIfOpen(RelayCodec.closeFrame(id)) }
        }
    }

    private fun storedMessage(url: String, message: RelayMessage) = synchronized(lock) {
        when (message) {
            is RelayMessage.Event -> storedQueries[message.subscriptionId]?.event(url, message.event)
            is RelayMessage.EndOfStoredEvents -> storedQueries[message.subscriptionId]?.end(url)
            is RelayMessage.Closed -> storedQueries[message.subscriptionId]?.failed(url)
            is RelayMessage.Ok -> publications[message.eventId]?.acknowledge(url, message.accepted)
            else -> Unit
        }
        Unit
    }

    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
        val id = "km-${nextSubscriptionId.incrementAndGet()}"
        val subscription = PoolSubscription(id, filters)
        // The REQ goes out only once the collector is attached. Sending it in
        // `subscribe` instead would open a window where events arrive with
        // nobody listening, and a shared flow drops those on the floor - which
        // is exactly the roster entry that tells you somebody is already here.
        return subscription.events
            .onSubscription { open(subscription) }
            .onCompletion { close(subscription) }
    }

    private fun open(subscription: PoolSubscription) {
        val frame = RelayCodec.requestFrame(subscription.id, subscription.filters)
        val targets: List<RelayLink>
        synchronized(lock) {
            subscriptions[subscription.id] = subscription
            targets = links.values.toList()
        }
        // A REQ is not queued if the relay is down: on reconnect every live
        // subscription is re-sent wholesale, so queueing it here would only
        // send it twice.
        for (link in targets) link.sendIfOpen(frame)
    }

    private fun close(subscription: PoolSubscription) {
        val frame = RelayCodec.closeFrame(subscription.id)
        val targets: List<RelayLink>
        synchronized(lock) {
            subscriptions.remove(subscription.id)
            targets = links.values.toList()
        }
        for (link in targets) link.sendIfOpen(frame)
    }

    private fun deliver(subscriptionId: String, event: NostrEvent) {
        val subscription = synchronized(lock) { subscriptions[subscriptionId] } ?: return
        subscription.offer(event)
    }

    private fun onLinkOpen(link: RelayLink) {
        val live: List<PoolSubscription>
        synchronized(lock) { live = subscriptions.values.toList() }
        // Subscriptions do not survive a dropped socket, so re-send every live
        // REQ before anything else. Skipping this is how a client silently goes
        // deaf after a relay restart while still looking connected.
        for (subscription in live) link.sendIfOpen(RelayCodec.requestFrame(subscription.id, subscription.filters))
        link.flushOutbox(now())
        _connected.value = synchronized(lock) { links.values.filter { it.isOpen }.map { it.url }.toSet() }
    }

    private fun onLinkClosed(link: RelayLink) {
        synchronized(lock) { storedQueries.values.forEach { it.failed(link.url) } }
        _connected.value = synchronized(lock) { links.values.filter { it.isOpen }.map { it.url }.toSet() }
    }

    private fun launchLink(link: RelayLink): Job = scope.launch {
        var attempt = 0
        while (isActive) {
            val closed = CompletableDeferred<String>()
            var connectedAt: Long? = null
            val listener = object : RelaySocketListener {
                override fun onOpen() {
                    connectedAt = now()
                    link.isOpen = true
                    onLinkOpen(link)
                }

                override fun onMessage(text: String) {
                    val message = RelayCodec.parse(text)
                    storedMessage(link.url, message)
                    when (message) {
                        is RelayMessage.Event -> deliver(message.subscriptionId, message.event)
                        // Everything else is informational. A CLOSED from one
                        // relay does not end the subscription: the others are
                        // still carrying it.
                        else -> Unit
                    }
                }

                override fun onClosed(reason: String) {
                    link.isOpen = false
                    link.socket = null
                    onLinkClosed(link)
                    closed.complete(reason)
                }
            }

            val socket = runCatching { sockets.open(link.url, listener) }.getOrNull()
            if (socket == null) {
                closed.complete("could not open")
            } else {
                link.socket = socket
                closed.await()
            }

            if (!isActive) break
            // Measured from the socket actually opening, not from the attempt
            // starting: a relay that takes twenty seconds to refuse a connection
            // has not been healthy for twenty seconds.
            val lasted = connectedAt?.let { now() - it } ?: 0
            attempt = if (lasted >= policy.stableAfterMs) 0 else attempt + 1
            // Full jitter: a uniform draw from zero to the backoff ceiling, so a
            // relay restart does not bring every device in the room back at the
            // same instant.
            val ceiling = policy.delayFor(attempt)
            delay(random.nextLong(ceiling + 1))
        }
    }

    private inner class RelayLink(val url: String) {
        @Volatile
        var socket: RelaySocket? = null

        @Volatile
        var isOpen: Boolean = false
        var job: Job? = null

        private val outboxLock = Any()
        private val outbox = ArrayDeque<Pending>()

        fun sendIfOpen(frame: String) {
            val socket = socket ?: return
            runCatching { socket.send(frame) }
        }

        /**
         * Publishes now if the relay is up, and otherwise holds the frame until
         * it is. Joining a room means announcing immediately, which is normally
         * a few hundred milliseconds before the first socket finishes opening -
         * without this, the very first announce of every session is lost.
         */
        fun sendOrQueue(frame: String) {
            val socket = socket
            if (socket != null && isOpen) {
                runCatching { socket.send(frame) }
                return
            }
            synchronized(outboxLock) {
                outbox.addLast(Pending(frame, now()))
                while (outbox.size > policy.outboxLimit) outbox.removeFirst()
            }
        }

        fun flushOutbox(at: Long) {
            val ready: List<Pending>
            synchronized(outboxLock) {
                // A stale signalling frame is worse than a dropped one: by the
                // time a relay comes back the negotiation it belonged to has
                // moved on.
                ready = outbox.filter { at - it.queuedAt <= policy.outboxTtlMs }
                outbox.clear()
            }
            for (pending in ready) sendIfOpen(pending.frame)
        }
    }

    private class Pending(val frame: String, val queuedAt: Long)

    private class Publication(private val pending: Set<String>) {
        val result = CompletableDeferred<Boolean>()
        private val rejected = mutableSetOf<String>()
        fun acknowledge(url: String, accepted: Boolean) {
            if (url !in pending) return
            if (accepted) result.complete(true)
            else if (rejected.add(url) && rejected.containsAll(pending)) result.complete(false)
        }
    }

    private class StoredQuery(private val targets: Set<String>) {
        val result = CompletableDeferred<List<NostrEvent>>()
        private val ended = mutableSetOf<String>()
        private val events = linkedMapOf<String, NostrEvent>()
        private var bytes = 0L
        fun event(url: String, event: NostrEvent) {
            if (url !in targets || url in ended || result.isCompleted) return
            if (!dev.forgesworn.kithmoot.protocol.Events.verify(event)) return
            if (event.id in events) return
            bytes += event.toJson().toString().length * 2L
            if (events.size >= 2_048 || bytes > 4 * 1024 * 1024) {
                result.completeExceptionally(IllegalStateException("Stored relay query exceeded its limit"))
            } else events[event.id] = event
        }
        fun end(url: String) {
            if (url in targets) ended.add(url)
            if (ended.containsAll(targets)) result.complete(events.values.toList())
        }
        fun failed(url: String) {
            if (url in targets && url !in ended) result.completeExceptionally(IllegalStateException("Stored relay query was interrupted"))
        }
    }

    /**
     * One subscription across the whole pool.
     *
     * The [SeenEvents] here is what makes "publish everywhere" survivable: the
     * same event arrives once per relay, and the room must see it once.
     */
    private class PoolSubscription(val id: String, val filters: List<Filter>) {
        private val seen = SeenEvents()
        private val _events = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 256)
        val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

        fun offer(event: NostrEvent) {
            if (!seen.admit(event.id)) return
            _events.tryEmit(event)
        }
    }
}
