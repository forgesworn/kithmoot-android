package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.crypto.SecureTimingRandom
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Preserve the failing endpoint without displaying arbitrary relay-supplied text. */
class RelayHistoryException(val relay: String, val authenticationRequired: Boolean) :
    IllegalStateException("Stored relay query was refused by $relay")

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

    /** Durable work must not treat an unconfirmed queue operation as delivery. */
    suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long = 15_000): Boolean =
        throw UnsupportedOperationException("This transport cannot confirm durable publication")

    /** Fails if complete retained history cannot be established. */
    suspend fun queryStored(filters: List<Filter>, timeoutMs: Long = 15_000): List<NostrEvent> =
        throw UnsupportedOperationException("This transport cannot verify retained history")

    /**
     * A cold flow of matching events, de-duplicated across relays. Cancelling
     * the collector closes the subscription on every relay.
     */
    fun subscribe(filters: List<Filter>): Flow<NostrEvent>

    /**
     * The relay URLs this transport reads from and writes to, when it has
     * any. A consumer works out the lane a message took from these, and from
     * nothing on the wire; see `Lane.kt` in the protocol module.
     */
    fun describe(): List<String> = emptyList()

    /** The relays among [describe] the client knows to be boxes of the person's
     *  own circle. Only those are ever shown as sheltered. Link transport hints
     *  in a contact card alone cannot establish that ownership. */
    fun circleRelays(): Set<String> = emptySet()

    /** Stop every publication path before a room key transition begins. */
    suspend fun beginRekey() = Unit

    /** Drop work encrypted for the previous key before successor subscriptions start. */
    suspend fun rekey(roomKey: ByteArray) = Unit

    /** Reopen publication only after every room subscriber has moved. */
    fun completeRekey() = Unit

    /** Stable-room recovery control remains available while epoch traffic is blocked. */
    fun publishRecovery(event: NostrEvent) = publish(event)
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
    private val random: Random = SecureTimingRandom(),
    /** The relays the client knows to be boxes of the person's own circle, asked
     *  each time so a card added mid-room counts. See storage/ContactBook.kt. */
    private val circle: () -> Set<String> = { emptySet() },
    /** Opt-in NIP-42 authority. A missing entry keeps the relay public. */
    private val authenticators: RelayAuthenticatorProvider = RelayAuthenticatorProvider { null },
    private val readRelays: Set<String> = urls.toSet(),
    private val writeRelays: Set<String> = urls.toSet(),
) : RoomTransport {

    private val lock = Any()
    private val links = mutableMapOf<String, RelayLink>()
    private val subscriptions = linkedMapOf<String, PoolSubscription>()
    private val storedQueries = linkedMapOf<String, StoredQuery>()
    private val negentropyQueries = linkedMapOf<String, NegentropyQuery>()
    private val nip77FetchQueries = linkedMapOf<String, Nip77FetchQuery>()
    private val nip77OfferPublications = linkedMapOf<String, Nip77OfferPublication>()
    private val publications = linkedMapOf<String, Publication>()
    private val attemptedWrites = linkedSetOf<String>()
    private fun trackWrite(event: NostrEvent) = synchronized(lock) {
        attemptedWrites.add(event.id)
        while (attemptedWrites.size > 512) attemptedWrites.remove(attemptedWrites.first())
    }
    private val nextSubscriptionId = AtomicLong(0)
    private var started = false
    @Volatile private var publicationBlocked = false

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    private val _health = MutableStateFlow(urls.associateWith { RelayHealth() })
    val health: StateFlow<Map<String, RelayHealth>> = _health.asStateFlow()
    private fun health(url: String, change: (RelayHealth) -> RelayHealth) = synchronized(lock) {
        _health.value = _health.value + (url to change(_health.value[url] ?: RelayHealth()))
    }

    /** Which relays are up right now. The interface shows this; a room with one relay left still works. */
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    /** Every relay we were asked to use, up or not. */
    val relayUrls: List<String> get() = urls

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            for (url in urls.filter { it in readRelays || it in writeRelays }) links[url] = RelayLink(url).also { it.job = launchLink(it) }
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
            negentropyQueries.values.forEach { it.fail("Relays stopped") }
            negentropyQueries.clear()
            nip77FetchQueries.values.forEach { it.fail("Relays stopped") }
            nip77FetchQueries.clear()
            publications.values.forEach { it.result.complete(false) }
            publications.clear()
        }
        for (link in closing) {
            link.authJob?.cancel()
            link.job?.cancel()
            runCatching { link.socket?.close() }
        }
        _connected.value = emptySet()
        urls.forEach { url -> health(url) { it.copy(connection = "Not connected") } }
    }

    override fun describe(): List<String> = urls.toList()

    override fun circleRelays(): Set<String> = circle()

    /**
     * Compare a small local index with one verified circle box. This accepts
     * only a canonical Link route which has completed this pool's NIP-42
     * handshake. The result contains IDs only: event fetch and custody are
     * deliberately separate operations.
     */
    suspend fun reconcileNip77(
        url: String,
        filter: Filter,
        records: Collection<Nip77Record>,
        timeoutMs: Long = 60_000,
    ): Nip77Reconciliation = withTimeout(timeoutMs) {
        require(timeoutMs in 1..60_000) { "NIP-77 sessions are limited to one minute" }
        require(LinkRelayAddress.canonical(url) == url) { "NIP-77 requires a canonical Link relay" }
        require(url in circle()) { "NIP-77 requires a verified circle box" }
        require(authenticators.forUrl(url) != null) { "NIP-77 requires NIP-42 permission" }
        validateNegentropyFilter(filter)
        val codec = Nip77Negentropy(records)
        connected.first { url in it }
        val id = "km-neg-${nextSubscriptionId.incrementAndGet()}"
        val query = NegentropyQuery(url, codec)
        val link = synchronized(lock) {
            val current = links[url] ?: throw IllegalStateException("NIP-77 relay is not started")
            check(current.isOpen && current.authState == AuthState.READY) { "NIP-77 relay is not authenticated" }
            check(authenticators.forUrl(url) != null) { "NIP-42 permission was withdrawn" }
            negentropyQueries[id] = query
            current
        }
        try {
            link.sendIfOpen(RelayCodec.negOpenFrame(id, filter, codec.initiate()))
            query.result.await()
        } finally {
            synchronized(lock) { negentropyQueries.remove(id) }
            link.sendIfOpen(RelayCodec.negCloseFrame(id))
        }
    }

    /**
     * Fetches only IDs disclosed by a completed, person-triggered NIP-77
     * comparison. This is a normal Nostr request, but it is constrained to the
     * same authenticated Link route and refuses every unsolicited record.
     * Callers still have to decrypt and validate returned events before showing
     * or retaining anything.
     */
    suspend fun fetchNip77Events(
        url: String,
        filter: Filter,
        ids: Collection<String>,
        timeoutMs: Long = 30_000,
    ): List<NostrEvent> = withTimeout(timeoutMs) {
        require(timeoutMs in 1..30_000) { "NIP-77 fetches are limited to thirty seconds" }
        require(LinkRelayAddress.canonical(url) == url) { "NIP-77 fetch requires a canonical Link relay" }
        require(url in circle()) { "NIP-77 fetch requires a verified circle box" }
        require(authenticators.forUrl(url) != null) { "NIP-77 fetch requires NIP-42 permission" }
        val expected = validateNip77FetchFilter(filter, ids)
        connected.first { url in it }
        val id = "km-neg-fetch-${nextSubscriptionId.incrementAndGet()}"
        val query = Nip77FetchQuery(url, filter, expected)
        val link = synchronized(lock) {
            val current = links[url] ?: throw IllegalStateException("NIP-77 relay is not started")
            check(current.isOpen && current.authState == AuthState.READY) { "NIP-77 relay is not authenticated" }
            check(authenticators.forUrl(url) != null) { "NIP-42 permission was withdrawn" }
            nip77FetchQueries[id] = query
            current
        }
        try {
            link.sendIfOpen(RelayCodec.requestFrame(id, listOf(filter)))
            query.result.await()
        } finally {
            synchronized(lock) { nip77FetchQueries.remove(id) }
            link.sendIfOpen(RelayCodec.closeFrame(id))
        }
    }

    /**
     * Publishes exact, already-compared phone-only room events to one current
     * Link/NIP-42-authorised Bothy. This never uses the ordinary fan-out
     * outbox: a custody offer is explicit, bounded and cannot become a public
     * relay retry.
     */
    suspend fun offerNip77Events(
        url: String,
        filter: Filter,
        events: Collection<NostrEvent>,
        timeoutMs: Long = 30_000,
    ): Int = withTimeout(timeoutMs) {
        require(timeoutMs in 1..30_000) { "NIP-77 offers are limited to thirty seconds" }
        require(LinkRelayAddress.canonical(url) == url) { "NIP-77 offer requires a canonical Link relay" }
        require(url in circle()) { "NIP-77 offer requires a verified circle box" }
        require(authenticators.forUrl(url) != null) { "NIP-77 offer requires NIP-42 permission" }
        val expected = validateNip77OfferEvents(filter, events)
        connected.first { url in it }
        val publication = Nip77OfferPublication(url, expected)
        val link = synchronized(lock) {
            val current = links[url] ?: throw IllegalStateException("NIP-77 relay is not started")
            check(current.isOpen && current.authState == AuthState.READY) { "NIP-77 relay is not authenticated" }
            check(authenticators.forUrl(url) != null) { "NIP-42 permission was withdrawn" }
            expected.forEach { id -> nip77OfferPublications[id] = publication }
            current
        }
        try {
            check(nip77StillPermitted(url)) { "NIP-77 authority was withdrawn" }
            events.sortedBy(NostrEvent::id).forEach { link.sendIfOpen(RelayCodec.publishFrame(it)) }
            publication.result.await()
            expected.size
        } finally {
            synchronized(lock) { expected.forEach { id -> nip77OfferPublications.remove(id, publication) } }
        }
    }

    /** A refused sheltered route remains inert until a person deliberately retries it. */
    fun retryAuthentication(url: String): Boolean = synchronized(lock) {
        val link = links[url] ?: return@synchronized false
        if (link.authState != AuthState.BLOCKED) return@synchronized false
        link.authState = AuthState.CLOSED
        link.authJob?.cancel()
        link.job = launchLink(link)
        true
    }

    /**
     * How many frames are waiting for a relay to come back, across every link.
     *
     * Diagnosis only. A room that looks joined while this stays high is a room
     * whose announcements never left the phone, which from the inside is
     * indistinguishable from a room nobody else is in.
     */
    fun outboxDepth(): Int = synchronized(lock) { links.values.sumOf { it.outboxDepth() } }

    override fun publish(event: NostrEvent) {
        check(!publicationBlocked) { "Room publication is blocked during a secure update" }
        trackWrite(event)
        val frame = RelayCodec.publishFrame(event)
        val targets: List<RelayLink>
        synchronized(lock) { targets = links.values.filter { it.url in writeRelays } }
        for (link in targets) link.sendOrQueue(frame)
    }

    override fun publishRecovery(event: NostrEvent) {
        require(event.kind in setOf(1462, 20_468, 20_469)) { "event is not room recovery control" }
        trackWrite(event)
        val frame = RelayCodec.publishFrame(event)
        val targets = synchronized(lock) { links.values.filter { it.url in writeRelays } }
        targets.forEach { it.sendOrQueue(frame) }
    }

    /** Confirm storage before exposing a durable link. An OK from any connected relay suffices. */
    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean = withTimeout(timeoutMs) {
        check(!publicationBlocked) { "Room publication is blocked during a secure update" }
        trackWrite(event)
        check(writeRelays.isNotEmpty()) { "No write relay is selected" }
        connected.first { connected -> connected.any { it in writeRelays } }
        val publication: Publication
        val targets: List<RelayLink>
        synchronized(lock) {
            check(!publicationBlocked) { "Room publication is blocked during a secure update" }
            targets = links.values.filter { it.isOpen && it.url in writeRelays }
            check(targets.isNotEmpty()) { "No relay is connected" }
            check(event.id !in publications) { "Event publication is already pending" }
            publication = Publication(targets.map { it.url }.toSet())
            publications[event.id] = publication
        }
        try {
            targets.forEach { it.sendIfOpen(RelayCodec.publishFrame(event)) }
            publication.result.await()
        } finally { synchronized(lock) {
            if (!publication.result.isCompleted) targets.forEach { target -> health(target.url) { it.copy(write = "No acknowledgement") } }
            publications.remove(event.id)
        } }
    }

    override suspend fun beginRekey() {
        publicationBlocked = true
        val current = synchronized(lock) {
            publications.values.forEach { it.result.complete(false) }
            links.values.toList()
        }
        current.forEach { it.clearOutbox() }
    }

    override suspend fun rekey(roomKey: ByteArray) {
        require(roomKey.size == 32) { "room key must be 32 bytes" }
        check(publicationBlocked) { "room publication must be blocked before rekey" }
        synchronized(lock) { links.values.toList() }.forEach { it.clearOutbox() }
    }

    override fun completeRekey() {
        check(publicationBlocked) { "room rekey is not in progress" }
        publicationBlocked = false
    }

    /** A complete snapshot from the currently connected relays. Disconnection, CLOSED,
     * overflow or missing EOSE fails the query; partial events never become admission. */
    override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> = withTimeout(timeoutMs) {
        check(readRelays.isNotEmpty()) { "No read relay is selected" }
        connected.first { connected -> connected.any { it in readRelays } }
        val id = "km-stored-${nextSubscriptionId.incrementAndGet()}"
        val query: StoredQuery
        val targets: List<RelayLink>
        synchronized(lock) {
            targets = links.values.filter { it.isOpen && it.url in readRelays }
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
            is RelayMessage.Closed -> storedQueries[message.subscriptionId]?.refused(url, message.message)
            is RelayMessage.Ok -> {
                publications[message.eventId]?.acknowledge(url, message.accepted)
                nip77OfferPublications[message.eventId]?.let { offer ->
                    if (nip77StillPermitted(url)) offer.acknowledge(url, message.eventId, message.accepted)
                    else offer.fail("NIP-77 authority was withdrawn")
                }
            }
            else -> Unit
        }
        Unit
    }

    private fun nip77FetchMessage(url: String, message: RelayMessage) {
        val query = when (message) {
            is RelayMessage.Event -> synchronized(lock) { nip77FetchQueries[message.subscriptionId] }
            is RelayMessage.EndOfStoredEvents -> synchronized(lock) { nip77FetchQueries[message.subscriptionId] }
            is RelayMessage.Closed -> synchronized(lock) { nip77FetchQueries[message.subscriptionId] }
            else -> null
        } ?: return
        if (query.url != url) return
        if (!nip77StillPermitted(url)) {
            query.fail("NIP-77 authority was withdrawn")
            return
        }
        when (message) {
            is RelayMessage.Event -> query.event(message.event)
            is RelayMessage.EndOfStoredEvents -> query.end()
            is RelayMessage.Closed -> query.fail("NIP-77 fetch was interrupted")
            else -> Unit
        }
    }

    private fun negentropyMessage(url: String, message: RelayMessage) {
        val query = when (message) {
            is RelayMessage.NegentropyMessage -> synchronized(lock) { negentropyQueries[message.subscriptionId] }
            is RelayMessage.NegentropyError -> synchronized(lock) { negentropyQueries[message.subscriptionId] }
            else -> null
        } ?: return
        if (query.url != url) return
        if (!nip77StillPermitted(url)) {
            query.fail("NIP-77 authority was withdrawn")
            return
        }
        when (message) {
            is RelayMessage.NegentropyMessage -> query.accept(message.payload)?.let { outbound ->
                synchronized(lock) { links[url] }?.sendIfOpen(RelayCodec.negMessageFrame(message.subscriptionId, outbound))
            }
            is RelayMessage.NegentropyError -> query.fail("NIP-77 relay refused the query: ${message.message}")
            else -> Unit
        }
    }

    private fun nip77StillPermitted(url: String): Boolean = url in circle() && authenticators.forUrl(url) != null && synchronized(lock) {
        links[url]?.let { it.isOpen && it.authState == AuthState.READY } == true
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
            targets = links.values.filter { it.url in readRelays }
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
            targets = links.values.filter { it.url in readRelays }
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
        if (link.url in readRelays) for (subscription in live) link.sendIfOpen(RelayCodec.requestFrame(subscription.id, subscription.filters))
        if (link.url in writeRelays) link.flushOutbox(now())
        health(link.url) { it.copy(connection = "Connected") }
        _connected.value = synchronized(lock) { links.values.filter { it.isOpen }.map { it.url }.toSet() }
    }

    private fun onLinkClosed(link: RelayLink) {
        synchronized(lock) { storedQueries.values.forEach { it.failed(link.url) } }
        synchronized(lock) { negentropyQueries.values.filter { it.url == link.url }.forEach { it.fail("NIP-77 relay closed") } }
        synchronized(lock) { nip77FetchQueries.values.filter { it.url == link.url }.forEach { it.fail("NIP-77 relay closed") } }
        synchronized(lock) { nip77OfferPublications.values.filter { it.url == link.url }.toSet().forEach { it.fail("NIP-77 relay closed") } }
        _connected.value = synchronized(lock) { links.values.filter { it.isOpen }.map { it.url }.toSet() }
    }

    private fun beginAuthentication(link: RelayLink, challenge: String) {
        val authenticator: RelayAuthenticator
        val generation: Long
        synchronized(lock) {
            if (!started || link.authState !in setOf(AuthState.AWAITING_CHALLENGE, AuthState.SIGNING, AuthState.AWAITING_OK)) return
            authenticator = authenticators.forUrl(link.url) ?: return
            generation = link.socketGeneration
            link.authJob?.cancel()
            link.authState = AuthState.SIGNING
        }
        link.authJob = scope.launch {
            val event = runCatching { authenticator.sign(link.url, challenge) }.getOrNull()
            synchronized(lock) {
                val socketCurrent = started && links[link.url] === link &&
                    link.socketGeneration == generation && link.authState == AuthState.SIGNING
                if (!socketCurrent) return@synchronized
                val currentAuthenticator = authenticators.forUrl(link.url)
                if (currentAuthenticator?.pubkey != authenticator.pubkey || event == null ||
                    !validAuth(event, authenticator, link.url, challenge)
                ) {
                    // A route that lost consent while the signer was showing
                    // must be closed, not merely have its late result ignored.
                    block(link)
                    return@synchronized
                }
                val socket = link.socket ?: return@synchronized block(link)
                runCatching { socket.send(RelayCodec.authFrame(event)) }.onFailure { block(link) }
                if (link.authState == AuthState.SIGNING) {
                    link.authEventId = event.id
                    link.authState = AuthState.AWAITING_OK
                }
            }
        }
    }

    private fun validAuth(event: NostrEvent, authenticator: RelayAuthenticator, url: String, challenge: String): Boolean =
        event.pubkey == authenticator.pubkey && event.kind == 22242 && event.content.isEmpty() &&
            event.tags == listOf(listOf("relay", url), listOf("challenge", challenge)) && Events.verify(event)

    /** Must be called under [lock]. Closing also wakes the reconnect loop; BLOCKED then stops it. */
    private fun block(link: RelayLink) {
        link.authState = AuthState.BLOCKED
        link.isOpen = false
        link.authJob?.cancel()
        runCatching { link.socket?.close() }
        link.closed?.complete("authentication blocked")
    }

    private fun authenticationOk(link: RelayLink, message: RelayMessage.Ok): Boolean = synchronized(lock) {
        if (link.authState != AuthState.AWAITING_OK || message.eventId != link.authEventId) return@synchronized false
        if (!message.accepted) {
            block(link)
            return@synchronized false
        }
        link.authState = AuthState.READY
        link.authEventId = null
        link.isOpen = true
        true
    }

    private fun launchLink(link: RelayLink): Job = scope.launch {
        var attempt = 0
        while (isActive) {
            health(link.url) { it.copy(connection = if (attempt == 0) "Connecting" else "Reconnecting") }
            val closed = CompletableDeferred<String>()
            link.closed = closed
            var connectedAt: Long? = null
            // CONNECTING until the socket opens or the pool gives up on it.
            // Whichever comes first wins; the loser is ignored.
            val phase = AtomicInteger(CONNECTING)
            var socket: RelaySocket? = null
            val listener = object : RelaySocketListener {
                override fun onOpen() {
                    if (!phase.compareAndSet(CONNECTING, OPEN)) {
                        runCatching { socket?.close() }
                        return
                    }
                    connectedAt = now()
                    val requiresAuth = authenticators.forUrl(link.url) != null
                    synchronized(lock) {
                        link.socketGeneration += 1
                        link.authState = if (requiresAuth) AuthState.AWAITING_CHALLENGE else AuthState.READY
                        link.isOpen = !requiresAuth
                    }
                    if (!requiresAuth) onLinkOpen(link)
                }

                override fun onMessage(text: String) {
                    if (phase.get() == ABANDONED) return
                    val message = RelayCodec.parse(text)
                    if (message is RelayMessage.Auth) beginAuthentication(link, message.challenge)
                    if (message is RelayMessage.Ok && authenticationOk(link, message)) onLinkOpen(link)
                    storedMessage(link.url, message)
                    negentropyMessage(link.url, message)
                    nip77FetchMessage(link.url, message)
                    when (message) {
                        is RelayMessage.Event -> if (link.url in readRelays) deliver(message.subscriptionId, message.event)
                        is RelayMessage.EndOfStoredEvents -> health(link.url) { it.copy(read = "History read confirmed") }
                        is RelayMessage.Closed -> health(link.url) { it.copy(read = if (message.message.contains("auth-required")) "Authentication required" else "Read refused") }
                        is RelayMessage.Ok -> if (link.url in writeRelays && synchronized(lock) { message.eventId in attemptedWrites }) health(link.url) { it.copy(write = if (message.accepted) "Write accepted" else "Write refused") }
                        is RelayMessage.Auth -> if (authenticators.forUrl(link.url) == null) health(link.url) { it.copy(read = "Relay requests authentication") }
                        // Everything else is informational. A CLOSED from one
                        // relay does not end the subscription: the others are
                        // still carrying it.
                        else -> Unit
                    }
                }

                override fun onClosed(reason: String) {
                    // Already written off and already being retried.
                    if (phase.get() == ABANDONED) return
                    link.authJob?.cancel()
                    link.socketGeneration += 1
                    link.isOpen = false
                    link.socket = null
                    if (link.authState != AuthState.BLOCKED) link.authState = AuthState.CLOSED
                    onLinkClosed(link)
                    health(link.url) { it.copy(connection = "Disconnected; retrying") }
                    closed.complete(reason)
                }
            }

            socket = runCatching { sockets.open(link.url, listener) }.getOrNull()
            if (socket == null) {
                health(link.url) { it.copy(connection = "Connection failed; retrying") }
                closed.complete("could not open")
            } else {
                link.socket = socket
                // A relay can accept the connection and then never answer the
                // upgrade. Nothing below the pool times that out, and a link
                // left waiting on it would never be retried, so the pool
                // abandons it here and tries again like any other drop.
                val watchdog = launch {
                    delay(policy.openTimeoutMs)
                    if (!phase.compareAndSet(CONNECTING, ABANDONED)) return@launch
                    link.socket = null
                    runCatching { socket?.close() }
                    health(link.url) { it.copy(connection = "No answer; retrying") }
                    closed.complete("no answer within ${policy.openTimeoutMs} ms")
                }
                closed.await()
                watchdog.cancel()
            }

            if (!isActive) break
            if (link.authState == AuthState.BLOCKED) break
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

    private companion object {
        const val CONNECTING = 0
        const val OPEN = 1
        const val ABANDONED = 2
    }

    private inner class RelayLink(val url: String) {
        @Volatile
        var socket: RelaySocket? = null

        @Volatile
        var isOpen: Boolean = false
        var job: Job? = null
        @Volatile var authState: AuthState = AuthState.CLOSED
        @Volatile var socketGeneration: Long = 0
        @Volatile var authEventId: String? = null
        @Volatile var authJob: Job? = null
        @Volatile var closed: CompletableDeferred<String>? = null

        private val outboxLock = Any()
        private val outbox = ArrayDeque<Pending>()

        fun sendIfOpen(frame: String) {
            if (frame.startsWith("[\"EVENT\",") && url !in writeRelays) return
            if ((frame.startsWith("[\"REQ\",") || frame.startsWith("[\"NEG-OPEN\",") || frame.startsWith("[\"NEG-MSG\",")) && url !in readRelays) return
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
            if (url !in writeRelays) return
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

        fun clearOutbox() = synchronized(outboxLock) { outbox.clear() }

        fun outboxDepth(): Int = synchronized(outboxLock) { outbox.size }
    }

    private enum class AuthState { CLOSED, AWAITING_CHALLENGE, SIGNING, AWAITING_OK, READY, BLOCKED }

    private class NegentropyQuery(val url: String, private val codec: Nip77Negentropy) {
        val result = CompletableDeferred<Nip77Reconciliation>()
        private val have = linkedMapOf<String, ByteArray>()
        private val need = linkedMapOf<String, ByteArray>()
        private var rounds = 1

        @Synchronized fun accept(payload: ByteArray): ByteArray? = try {
            val step = codec.reconcile(payload)
            step.have.forEach { have.putIfAbsent(it.toHex(), it.copyOf()) }
            step.need.forEach { need.putIfAbsent(it.toHex(), it.copyOf()) }
            val next = step.nextMessage
            when {
                next == null -> {
                    result.complete(Nip77Reconciliation(have.values.map(ByteArray::copyOf), need.values.map(ByteArray::copyOf), null))
                    null
                }
                ++rounds > 16 -> {
                    fail("NIP-77 round limit reached")
                    null
                }
                else -> next
            }
        } catch (error: Exception) {
            fail(error.message ?: "invalid NIP-77 relay message")
            null
        }

        @Synchronized fun fail(message: String) { result.completeExceptionally(IllegalStateException(message)) }
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun validateNegentropyFilter(filter: Filter) {
        val since = requireNotNull(filter.since) { "NIP-77 requires a bounded history window" }
        val until = requireNotNull(filter.until) { "NIP-77 requires a bounded history window" }
        require(since >= 0 && until >= since && until - since <= 30L * 24 * 60 * 60) { "NIP-77 history window is invalid" }
        require(filter.kinds?.size == 1 && filter.limit in 1..Nip77Negentropy.MAX_RECORDS) { "NIP-77 filter is not narrow enough" }
    }

    private fun validateNip77FetchFilter(filter: Filter, ids: Collection<String>): Set<String> {
        validateNegentropyFilter(filter)
        val expected = ids.toSet()
        require(expected.size in 1..Nip77Negentropy.MAX_RECORDS && expected.all(::isCanonicalEventId)) {
            "NIP-77 fetch IDs are not bounded canonical event IDs"
        }
        require(filter.ids?.size == expected.size && filter.ids.toSet() == expected && filter.ids.all(::isCanonicalEventId)) {
            "NIP-77 fetch filter must name exactly the compared IDs"
        }
        require(filter.limit == expected.size) { "NIP-77 fetch limit must equal the compared ID count" }
        return expected
    }

    private fun validateNip77OfferEvents(filter: Filter, events: Collection<NostrEvent>): Set<String> {
        val expected = validateNip77FetchFilter(filter, events.map(NostrEvent::id))
        require(events.size == expected.size && events.map(NostrEvent::id).toSet() == expected) {
            "NIP-77 offer events must have unique compared IDs"
        }
        val since = requireNotNull(filter.since)
        val until = requireNotNull(filter.until)
        require(events.all { event ->
            Events.verify(event) && event.kind in requireNotNull(filter.kinds) &&
                event.createdAt in since..until && filter.tags.all { (name, values) ->
                    name.length == 2 && name[0] == '#' && values.isNotEmpty() &&
                        event.tags.any { tag -> tag.size >= 2 && tag[0] == name.substring(1) && tag[1] in values }
                }
        }) { "NIP-77 offer contains an event outside its compared room window" }
        return expected
    }

    private fun isCanonicalEventId(id: String): Boolean =
        id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }

    private class Pending(val frame: String, val queuedAt: Long)

    /** One exact-ID read from a NIP-77-authorised box, never a general history query. */
    private class Nip77FetchQuery(
        val url: String,
        private val filter: Filter,
        private val expectedIds: Set<String>,
    ) {
        val result = CompletableDeferred<List<NostrEvent>>()
        private val events = linkedMapOf<String, NostrEvent>()

        @Synchronized fun event(event: NostrEvent) {
            if (result.isCompleted) return
            if (!Events.verify(event)) return fail("NIP-77 fetch returned an unverified event")
            if (event.id !in expectedIds || event.kind !in requireNotNull(filter.kinds)) {
                return fail("NIP-77 fetch returned an unexpected event")
            }
            val since = requireNotNull(filter.since)
            val until = requireNotNull(filter.until)
            if (event.createdAt !in since..until || !matchesTags(event, filter.tags)) {
                return fail("NIP-77 fetch returned an event outside its compared room window")
            }
            if (events.putIfAbsent(event.id, event) == null && events.size > expectedIds.size) {
                fail("NIP-77 fetch exceeded its compared ID limit")
            }
        }

        @Synchronized fun end() {
            if (!result.isCompleted) result.complete(events.values.toList())
        }

        @Synchronized fun fail(message: String) {
            result.completeExceptionally(IllegalStateException(message))
        }

        private fun matchesTags(event: NostrEvent, required: Map<String, List<String>>): Boolean =
            required.all { (name, values) ->
                name.length == 2 && name[0] == '#' && values.isNotEmpty() &&
                    event.tags.any { tag -> tag.size >= 2 && tag[0] == name.substring(1) && tag[1] in values }
            }
    }

    /** One exact-ID offer to one box. Any refusal or authority loss fails all IDs. */
    private class Nip77OfferPublication(val url: String, expected: Set<String>) {
        val result = CompletableDeferred<Unit>()
        private val pending = expected.toMutableSet()

        @Synchronized fun acknowledge(from: String, id: String, accepted: Boolean) {
            if (result.isCompleted || from != url || id !in pending) return
            if (!accepted) return fail("NIP-77 box refused a custody offer")
            pending.remove(id)
            if (pending.isEmpty()) result.complete(Unit)
        }

        @Synchronized fun fail(message: String) {
            result.completeExceptionally(IllegalStateException(message))
        }
    }

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
        fun refused(url: String, reason: String) {
            if (url in targets && url !in ended) result.completeExceptionally(
                RelayHistoryException(url, reason.contains("auth-required:", ignoreCase = true)))
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
