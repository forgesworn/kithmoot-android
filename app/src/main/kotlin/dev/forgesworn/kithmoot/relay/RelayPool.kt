package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.crypto.SecureTimingRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Preserve the failing endpoint without displaying arbitrary relay-supplied text. */
class RelayHistoryException(val relay: String, val authenticationRequired: Boolean) :
    IllegalStateException("Stored relay query was refused by $relay")

/** A transport offered the event but has no receipt proving durable acceptance.
 * Distinct from a refusal and from failing before anything left the device. */
class PublicationUnconfirmedException : IllegalStateException("Publication was offered without a durable receipt")

/** Proof that this call rejected publication before offering to any lane.
 * A generic transport error cannot establish that nothing left the device. */
class PublicationNotOfferedException(message: String = "Publication was rejected before dispatch") : IllegalStateException(message)

/** Explicit absence of complete retained history, distinct from a failed
 * supported query or a corrupt local journal. Never means empty history. */
class StoredHistoryUnavailableException : UnsupportedOperationException("This transport cannot verify retained history")

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

    /** True means durable acceptance, false means refusal. An offered event
     * without such a receipt raises [PublicationUnconfirmedException]. */
    suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long = 15_000): Boolean =
        throw UnsupportedOperationException("This transport cannot confirm durable publication")

    /** Whether receiving this event can reconcile a pending publication. Mesh
     * local echoes and unauthenticated replay cannot provide that evidence. */
    fun receivedEventConfirmsPublication(eventId: String): Boolean = false

    /** Locally observed ingress, never sender-provided routing claims. */
    fun receivedViaRelays(eventId: String): List<String> = describe()

    /** Whether a write relay is connected now, so a durable send knows if offering
     *  it is worth a try. Cheap and in memory; a transport that cannot say answers yes. */
    fun reachable(): Boolean = true

    /** Whether a relay has said OK to this exact event while this transport has been up. In memory only. */
    fun relayAccepted(eventId: String): Boolean = false

    /** Token must be captured before reading the room epoch for a durable retry. */
    fun publicationGeneration(): Long = 0

    /** The guard is read at dispatch; it must be cheap and must not call the transport.
     * Only [PublicationNotOfferedException] proves rejection before any offer. */
    suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long = 15_000): Boolean =
        throw UnsupportedOperationException("This transport cannot guard a pending chat publication")

    /** Suggested wait for guarded delivery, including scheduled slots. Callers
     * still enforce their own credential, room and freshness deadlines. */
    fun guardedConfirmationTimeoutMs(kind: Int): Long = 15_000

    /** Fails if complete retained history cannot be established. */
    suspend fun queryStored(filters: List<Filter>, timeoutMs: Long = 15_000): List<NostrEvent> =
        throw StoredHistoryUnavailableException()

    /** Best effort: whatever the answering relays hold. Never for admission, credentials,
     *  rosters or any decision where a missing event would count as proof. */
    suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long = 15_000): List<NostrEvent> = queryStored(filters, timeoutMs)

    /**
     * A cold flow of matching events, de-duplicated across relays. Cancelling
     * the collector closes the subscription on every relay.
     */
    fun subscribe(filters: List<Filter>): Flow<NostrEvent>

    /** Completion follows delivery of stored events from every read relay.
     * Unsupported transports leave the caller on its existing timeout. */
    fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> = subscribe(filters)

    /**
     * [subscribe] for a long stored stream such as chat. The first REQ to each
     * relay asks for all of [filters]. After a reconnect, a relay this
     * subscription has already read to its EOSE is asked only from the newest
     * `created_at` that relay delivered, less [skewSeconds], plus a `limit: 1`
     * copy of the full filter so that a late-published event still arrives
     * live. [skewSeconds] must exceed the longest any client holds a signed
     * event before its first publish. A transport that cannot do this
     * subscribes with the full filters, which costs load and loses nothing.
     */
    fun subscribeCatchingUp(filters: () -> List<Filter>, skewSeconds: Long): Flow<NostrEvent> = subscribe(filters())

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

    /**
     * The room keys of epochs this room has left and still reads, newest
     * first, replacing whatever set was kept before. They only open what
     * arrives: nothing is ever sent under them.
     */
    fun keepPast(roomKeys: List<ByteArray>) = Unit

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
    // These three start as the constructor's arguments, and only ever grow
    // afterwards - see [addRelays] - so a room's authority can add relays
    // for everybody without a rejoin. Mutation is always under [lock];
    // `@Volatile` covers the plain reads the rest of this class already did
    // outside it before these were mutable at all.
    @Volatile private var urls: List<String>,
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
    @Volatile private var readRelays: Set<String> = urls.toSet(),
    @Volatile private var writeRelays: Set<String> = urls.toSet(),
    /** Public DM relays may challenge with NIP-42 but need not. Never weakens a Link or circle route. */
    private val publicAuthOnChallenge: Boolean = false,
    private val trace: RelaySessionTrace? = null,
    /** Relay refusals this process has been given, shared by every pool: see [RelayGate]. */
    private val gate: RelayGate = RelayGate.process,
) : RoomTransport {

    private val lock = Any()
    private val links = mutableMapOf<String, RelayLink>()
    @Volatile private var traceLinks: List<RelayLink> = emptyList()
    init { trace?.record(RelayTraceEvent.POOL_CREATE) }
    private val subscriptions = linkedMapOf<String, PoolSubscription>()
    private val storedQueries = linkedMapOf<String, StoredQuery>()
    private val negentropyQueries = linkedMapOf<String, NegentropyQuery>()
    private val nip77FetchQueries = linkedMapOf<String, Nip77FetchQuery>()
    private val nip77OfferPublications = linkedMapOf<String, Nip77OfferPublication>()
    private val publications = linkedMapOf<String, Publication>()
    private val attemptedWrites = linkedSetOf<String>()
    /** Reads a relay refused, by subscription, until that subscription is answered or ends:
     *  one subscription's history coming back must not hide another's being refused. */
    private val refusedReads = mutableMapOf<String, LinkedHashMap<String, String>>()
    private val confirmedReads = mutableSetOf<String>()
    private fun readAnswered(url: String, subscriptionId: String, refusal: String?) = synchronized(lock) {
        val refused = refusedReads.getOrPut(url) { linkedMapOf() }
        if (refusal == null) { refused.remove(subscriptionId); confirmedReads += url }
        else { refused[subscriptionId] = refusal; while (refused.size > 64) refused.remove(refused.keys.first()) }
        showRead(url)
    }
    private fun forgetRead(subscriptionId: String) = synchronized(lock) {
        refusedReads.forEach { (url, refused) -> if (refused.remove(subscriptionId) != null) showRead(url) }
    }
    private fun showRead(url: String) {
        val refused = refusedReads[url]?.values.orEmpty()
        val read = refused.firstOrNull { it == READ_REFUSED } ?: refused.firstOrNull()
            ?: if (url in confirmedReads) READ_CONFIRMED else null
        health(url) { it.copy(read = read ?: "Not checked") }
    }
    private val writeKinds = linkedMapOf<String, Int>()
    private fun trackWrite(event: NostrEvent) = synchronized(lock) {
        attemptedWrites.add(event.id)
        while (attemptedWrites.size > 512) attemptedWrites.remove(attemptedWrites.first())
        writeKinds[event.id] = event.kind
        while (writeKinds.size > 512) writeKinds.remove(writeKinds.keys.first())
    }
    /** Kinds already asked back from each relay since its socket last opened. */
    private val readBackChecked = mutableMapOf<String, MutableSet<Int>>()
    private val readBacks = linkedMapOf<String, ReadBack>()
    /** Kinds each relay has returned when asked back, since its socket last opened. */
    private val kept = mutableMapOf<String, MutableSet<Int>>()
    /** Which relays each written event of a stored kind went to, and what each said: see [publishEvidence]. */
    private val acknowledged = linkedMapOf<String, AckRecord>()
    /** Read-backs under way, as `url` and event id, so one relay is never asked about one event twice at once. */
    private val askedBack = mutableSetOf<String>()
    private class RelayAck(var acked: Boolean = false, var at: Long = 0, var found: Boolean? = null, var refused: Boolean = false)
    private class AckRecord(val kind: Int) { val relays = linkedMapOf<String, RelayAck>() }
    private fun ackRecord(eventId: String, kind: Int): AckRecord = acknowledged.getOrPut(eventId) { AckRecord(kind) }.also {
        while (acknowledged.size > MAX_ACKNOWLEDGED) acknowledged.remove(acknowledged.keys.first())
    }
    /** Under [lock]. A write offered again is a new question: what a relay that has not said OK to it answered before is dropped. */
    private fun noteOffered(event: NostrEvent, url: String) {
        if (!isStoredKind(event.kind)) return
        val relays = ackRecord(event.id, event.kind).relays
        if (relays[url]?.acked != true) relays[url] = RelayAck()
    }
    /** A relay's OK to an event this pool wrote. True when it had already said OK to it, or been asked
     *  about it: a write again, which is asked back by name whatever was seen of the kind before. */
    private fun noteAnswer(url: String, eventId: String, accepted: Boolean): Boolean = synchronized(lock) {
        val kind = writeKinds[eventId] ?: return false
        if (!isStoredKind(kind)) return false
        val relays = ackRecord(eventId, kind).relays
        val previous = relays[url]
        if (!accepted) {
            if (previous?.acked != true) relays[url] = RelayAck(refused = true)
            return false
        }
        relays[url] = RelayAck(acked = true, at = now())
        previous != null && (previous.acked || previous.found != null)
    }
    private val nextSubscriptionId = AtomicLong(0)
    private var started = false
    @Volatile private var publicationBlocked = false
    @Volatile private var keeperResetReady = true
    private val rekeyGeneration = MutableStateFlow(0L)

    override fun receivedEventConfirmsPublication(eventId: String): Boolean = true

    override fun publicationGeneration(): Long = synchronized(lock) { rekeyGeneration.value }

    override fun reachable(): Boolean = connected.value.any { it in writeRelays && !gate.isHeld(it, now()) }

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
            started = true; keeperResetReady = true
            trace?.record(RelayTraceEvent.POOL_START)
            for (url in urls.filter { it in readRelays || it in writeRelays }) links[url] = RelayLink(url).also { it.job = launchLink(it) }
            if (trace != null) traceLinks = links.values.toList()
        }
    }

    /**
     * Adds relays to a pool that is already running (or not yet started),
     * both reading and writing, without touching anything already open: a
     * new link joins exactly the way one does on reconnect - every live
     * subscription is re-sent to it and its outbox flushed once it opens,
     * in [onLinkOpen] - so nothing here duplicates that path. A relay
     * already in the pool is left alone. Used when a room's authority
     * shares more relays for everybody; see `RoomWork`'s `relays` op.
     */
    fun addRelays(new: List<String>) {
        val toAdd = new.filterNot { it in urls }
        if (toAdd.isEmpty()) return
        synchronized(lock) {
            urls = urls + toAdd
            readRelays = readRelays + toAdd
            writeRelays = writeRelays + toAdd
            _health.value = _health.value + toAdd.associateWith { RelayHealth() }
            if (started) for (url in toAdd) if (url !in links) links[url] = RelayLink(url).also { it.job = launchLink(it) }
            if (trace != null) traceLinks = links.values.toList()
        }
    }

    fun stop() {
        val closing: List<RelayLink>
        synchronized(lock) {
            if (!started) return
            started = false; keeperResetReady = false; rekeyGeneration.value += 1
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
            acknowledged.clear(); kept.clear()
        }
        for (link in closing) {
            link.authJob?.cancel()
            link.liftJob?.cancel()
            link.job?.cancel()
            runCatching { link.socket?.close() }
        }
        _connected.value = emptySet()
        trace?.record(RelayTraceEvent.POOL_STOP)
        trace?.record(RelayTraceEvent.CONNECTED_PUBLISHED, trace.nextRevision(), 0)
        traceLinks = emptyList()
        urls.forEach { url -> health(url) { it.copy(connection = "Not connected") } }
    }

    override fun describe(): List<String> = urls.toList()

    override fun circleRelays(): Set<String> = circle()

    /** Wait for this connection's verified NIP-42 OK, not merely its public socket open. */
    suspend fun awaitAuthentication(url: String, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        connected.first { open -> url in open && synchronized(lock) { links[url]?.authState == AuthState.READY } }
        true
    } ?: false

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
        for (link in targets) link.sendOrQueue(frame, event)
    }

    /**
     * [publish], to [only] of the write relays. For an event a caller re-offers
     * relay by relay, such as a retirement that one relay has not yet said OK
     * to, so the relays that have it are not asked again.
     */
    fun publishTo(event: NostrEvent, only: Set<String>) {
        check(!publicationBlocked) { "Room publication is blocked during a secure update" }
        trackWrite(event)
        val frame = RelayCodec.publishFrame(event)
        val targets = synchronized(lock) { links.values.filter { it.url in writeRelays && it.url in only } }
        for (link in targets) link.sendOrQueue(frame, event)
    }

    override fun relayAccepted(eventId: String): Boolean = acknowledgedBy(eventId).isNotEmpty()

    /** The write relays that have said OK to [eventId] since this pool started. */
    fun acknowledgedBy(eventId: String): Set<String> = synchronized(lock) {
        acknowledged[eventId]?.relays?.filterValues { it.acked }?.keys?.toSet().orEmpty()
    }

    override fun publishRecovery(event: NostrEvent) {
        require(event.kind in setOf(1462, 20_468, 20_469, 20_471, 20_472)) { "event is not room recovery control" }
        trackWrite(event)
        val frame = RelayCodec.publishFrame(event)
        val targets = synchronized(lock) { links.values.filter { it.url in writeRelays } }
        targets.forEach { it.sendOrQueue(frame, event) }
    }

    /** Confirm storage before exposing a durable link. An OK from any connected relay suffices. */
    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean =
        publishConfirmedAtGeneration(event, publicationGeneration(), { true }, timeoutMs)

    override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean =
        publishConfirmedAtGeneration(event, generation, stillAllowed, timeoutMs)

    /** Guarded original retirement/rekey only, available after queue reset
     * while ordinary publication is held. Relay OK is not a member receipt. */
    internal fun keeperControlReady(): Boolean = started && keeperResetReady && reachable()
    internal suspend fun publishKeeperControlGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        require(event.kind in setOf(1461, 1462) && dev.forgesworn.kithmoot.protocol.Events.verify(event))
        return publishConfirmedAtGeneration(event, generation, stillAllowed, timeoutMs, keeperControl = true)
    }
    internal suspend fun publishKeeperAnswerGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        require(event.kind in setOf(20467, 20469) && Events.verify(event))
        return publishConfirmedAtGeneration(event, generation, stillAllowed, timeoutMs, keeperControl = true)
    }

    internal suspend fun publishKeeperWelcomeGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        require(event.kind == 1463 && Events.verify(event))
        return publishConfirmedAtGeneration(event, generation, stillAllowed, timeoutMs, keeperControl = true)
    }

    private suspend fun publishConfirmedAtGeneration(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long, keeperControl: Boolean = false): Boolean = withTimeout(timeoutMs) {
        val operation = currentCoroutineContext().job
        if (!(if (keeperControl) started && keeperResetReady else !publicationBlocked) || generation != publicationGeneration() || !stillAllowed())
            throw PublicationNotOfferedException("Room publication is blocked during a secure update")
        if (writeRelays.isEmpty()) throw PublicationNotOfferedException("No write relay is selected")
        combine(connected, rekeyGeneration) { up, current -> up.any { it in writeRelays } to current }
            .first { (up, current) -> up || current != generation }
        val publication: Publication
        val targets: List<RelayLink>
        synchronized(lock) {
            if (!(if (keeperControl) started && keeperResetReady else !publicationBlocked) || rekeyGeneration.value != generation || !stillAllowed())
                throw PublicationNotOfferedException("Room publication is blocked during a secure update")
            val open = links.values.filter { it.isOpen && it.url in writeRelays }
            // A relay holding this phone off, or one that refused this very event for a
            // reason asking again cannot change, is not offered it: not now, not on retry.
            targets = open.filter { !gate.isHeld(it.url, now()) && !gate.eventStopped(it.url, event.id) }
            if (targets.isEmpty()) throw PublicationNotOfferedException(
                if (open.isEmpty()) "No relay is connected" else "The connected relays are not taking this message now")
            check(event.id !in publications) { "Event publication is already pending" }
            trackWrite(event)
            publication = Publication(targets.map { it.url }.toSet())
            publications[event.id] = publication
            // Enqueue under the same lock that begins a rekey. There is no
            // window in which an old event can slip out after that boundary.
            val frame = RelayCodec.publishFrame(event)
            targets.forEach { target ->
                // Another socket's synchronous callback can cancel the caller
                // or withdraw authority during fanout. Recheck each real write.
                if (operation.isActive && rekeyGeneration.value == generation && stillAllowed() &&
                    (if (keeperControl) started && keeperResetReady else !publicationBlocked)) {
                    noteOffered(event, target.url)
                    target.sendIfOpen(frame)
                }
            }
        }
        try {
            publication.result.await()
        } finally { synchronized(lock) {
            if (!publication.result.isCompleted) targets.forEach { target -> health(target.url) { it.copy(write = "No acknowledgement") } }
            publications.remove(event.id)
        } }
    }

    override suspend fun beginRekey() {
        val resetGeneration: Long
        val current = synchronized(lock) {
            publicationBlocked = true; keeperResetReady = false
            rekeyGeneration.value += 1; resetGeneration = rekeyGeneration.value
            publications.values.forEach { it.result.complete(false) }
            links.values.toList()
        }
        current.forEach { it.clearOutbox() }
        synchronized(lock) { if (started && rekeyGeneration.value == resetGeneration) keeperResetReady = true }
    }

    override suspend fun rekey(roomKey: ByteArray) {
        require(roomKey.size == 32) { "room key must be 32 bytes" }
        check(publicationBlocked) { "room publication must be blocked before rekey" }
        val generation = synchronized(lock) { keeperResetReady = false; rekeyGeneration.value }
        synchronized(lock) { links.values.toList() }.forEach { it.clearOutbox() }
        synchronized(lock) { if (started && rekeyGeneration.value == generation) keeperResetReady = true }
    }

    override fun completeRekey() {
        synchronized(lock) {
            check(publicationBlocked) { "room rekey is not in progress" }
            publicationBlocked = false
        }
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
            targets = links.values.filter { it.isOpen && it.url in readRelays && !gate.isHeld(it.url, now()) }
            check(targets.isNotEmpty()) { "No relay is connected" }
            query = StoredQuery(targets.map { it.url }.toSet())
            storedQueries[id] = query
        }
        try {
            targets.forEach { it.sendIfOpen(RelayCodec.requestFrame(id, filters)) }
            query.result.await()
        } finally {
            synchronized(lock) { storedQueries.remove(id) }
            forgetRead(id)
            targets.forEach { it.sendIfOpen(RelayCodec.closeFrame(id)) }
        }
    }

    /** Returns once every targeted relay has answered or gone, [RelayPolicy.storedGraceMs] after the
     * first EOSE, or at [timeoutMs], with what arrived. Fails only when no relay answered at all. */
    override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
        check(readRelays.isNotEmpty()) { "No read relay is selected" }
        val id = "km-stored-${nextSubscriptionId.incrementAndGet()}"
        var query: StoredQuery? = null
        var targets = emptyList<RelayLink>()
        try {
            withTimeoutOrNull(timeoutMs) {
                connected.first { connected -> connected.any { it in readRelays } }
                val q = synchronized(lock) {
                    targets = links.values.filter { it.isOpen && it.url in readRelays && !gate.isHeld(it.url, now()) }
                    check(targets.isNotEmpty()) { "No relay is connected" }
                    StoredQuery(targets.map { it.url }.toSet(), partial = true).also { query = it; storedQueries[id] = it }
                }
                targets.forEach { it.sendIfOpen(RelayCodec.requestFrame(id, filters)) }
                select<Unit> { q.result.onAwait {}; q.firstEnd.onAwait {} }
                if (!q.result.isCompleted) withTimeoutOrNull(policy.storedGraceMs) { q.result.await() }
            }
            val q = checkNotNull(query) { "No relay is connected" }
            synchronized(lock) { q.finish() }
            return q.result.await()
        } finally {
            synchronized(lock) { storedQueries.remove(id) }
            forgetRead(id)
            targets.forEach { it.sendIfOpen(RelayCodec.closeFrame(id)) }
        }
    }

    private fun storedMessage(url: String, message: RelayMessage) = synchronized(lock) {
        readBacks[(message as? RelayMessage.Event)?.subscriptionId ?: (message as? RelayMessage.EndOfStoredEvents)?.subscriptionId
            ?: (message as? RelayMessage.Closed)?.subscriptionId]?.takeIf { it.url == url }?.let { readBack ->
            when (message) {
                is RelayMessage.Event -> if (message.event.id == readBack.eventId && Events.verify(message.event)) readBack.found = true
                is RelayMessage.EndOfStoredEvents -> readBack.result.complete(readBack.found)
                else -> readBack.result.complete(null)
            }
        }
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

    /** Every relay in the pool subscribes with the same, fixed filter set. */
    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = subscribe({ filters })

    override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> =
        subscribe({ filters }, onReplayComplete = onReplayComplete)

    override fun subscribeCatchingUp(filters: () -> List<Filter>, skewSeconds: Long): Flow<NostrEvent> {
        require(skewSeconds >= 0)
        return subscribeWithControl(filters, null, {}, {}, keeperRequests = false, catchUpSkewSeconds = skewSeconds)
    }

    /** Identical original authority requests may retry after an answer is lost.
     * This bounded exception does not change ordinary room/chat deduplication. */
    internal fun subscribeKeeperRequests(filters: List<Filter>, onInstalled: () -> Unit = {}): Flow<NostrEvent> {
        require(filters.isNotEmpty() && filters.all { it.kinds?.let { kinds -> kinds.isNotEmpty() && kinds.all { k -> k in setOf(20466, 20468) } } == true })
        val frozen = filters.map { filter -> filter.copy(ids = filter.ids?.toList(),
            authors = filter.authors?.toList(), kinds = filter.kinds?.toList(),
            tags = filter.tags.mapValues { it.value.toList() }) }
        return subscribeWithControl({ frozen }, null, {}, {}, keeperRequests = true, onInstalled = onInstalled)
    }

    /**
     * A cold flow of matching events, de-duplicated across relays, with two
     * additions the fixed-filter overload cannot offer:
     *
     * - [filters] is evaluated fresh every time a REQ is actually sent - on
     *   first subscribe, and again on every reconnect - so a caller that
     *   advances a `since` watermark as events arrive never re-requests
     *   history it has already seen. The plain `List<Filter>` overload
     *   captures its argument once and re-sends the same frame forever,
     *   which is fine for a subscription with no watermark.
     * - [only], when given, restricts the REQ (and its CLOSE) to that subset
     *   of [readRelays] - never to a relay that would otherwise see it, so a
     *   relay that does not list a room never learns anything about it.
     * - [onEose] is called with the relay's URL each time that relay sends
     *   EOSE for this subscription, so a caller can tell "at least one
     *   relay has answered" apart from "every relay has".
     */
    fun subscribe(filters: () -> List<Filter>, only: Set<String>? = null, onEose: (url: String) -> Unit = {}, onReplayComplete: () -> Unit = {}): Flow<NostrEvent> {
        return subscribeWithControl(filters, only, onEose, onReplayComplete, keeperRequests = false)
    }
    private fun subscribeWithControl(filters: () -> List<Filter>, only: Set<String>?, onEose: (url: String) -> Unit,
        onReplayComplete: () -> Unit, keeperRequests: Boolean, onInstalled: () -> Unit = {},
        catchUpSkewSeconds: Long? = null): Flow<NostrEvent> {
        // The REQ goes out only once the collector is attached. Sending it in
        // `subscribe` instead would open a window where events arrive with
        // nobody listening, and a shared flow drops those on the floor - which
        // is exactly the roster entry that tells you somebody is already here.
        return flow {
            val seen = SeenEvents()
            var recoveries = 0
            var complete = false
            // A receive cursor can advance while this attempt is draining.
            // Recovery must still include the window whose events were lost.
            val recoveryFilters = filters()
            emitAll(flow {
                val id = "km-${nextSubscriptionId.incrementAndGet()}"
                var caughtUp = false
                val subscription = PoolSubscription(id,
                    { if (recoveries > 0 && !caughtUp) recoveryFilters else filters() },
                    only, onEose, if (keeperRequests) now else null, seen, catchUpSkewSeconds)
                val replayed = mutableSetOf<String>()
                emitAll(subscription.events
                    .onSubscription {
                        open(subscription)
                        // Registration is local, not remote delivery or EOSE.
                        onInstalled()
                    }
                    .transform { item ->
                        item.event?.let { emit(it) }
                        item.eose?.let { replayed += it }
                        // Completion follows the collector's checks, and only
                        // a loss-free attempt can establish complete replay.
                        if (!subscription.lostEvents && readRelays.isNotEmpty() && replayed.containsAll(readRelays)) {
                            caughtUp = true
                            if (!complete) { complete = true; onReplayComplete() }
                        }
                        // Drain before retrying, with a fresh wire ID so late
                        // EOSE from the old read cannot complete the new one.
                        // Keep successful IDs, never IDs refused by the buffer.
                        // The retry budget is bounded by the deduplication
                        // window; exhaustion continues live without claiming
                        // complete history. Keeper repeat budgets are unchanged.
                        if (subscription.consumed(!keeperRequests && recoveries < MAX_SUBSCRIPTION_RECOVERIES)) {
                            recoveries++
                            throw SubscriptionOverflow()
                        }
                    }
                    .onCompletion { close(subscription) })
            }.retryWhen { cause, _ -> cause is SubscriptionOverflow })
        }
    }

    private fun subscriptionTargets(subscription: PoolSubscription): List<RelayLink> = synchronized(lock) {
        links.values.filter { it.url in readRelays && (subscription.only == null || it.url in subscription.only) }
    }

    private fun open(subscription: PoolSubscription) {
        val targets: List<RelayLink>
        synchronized(lock) {
            subscriptions[subscription.id] = subscription
        }
        targets = subscriptionTargets(subscription)
        // A REQ is not queued if the relay is down: on reconnect every live
        // subscription is re-sent wholesale, so queueing it here would only
        // send it twice. One the relay is holding off waits for the hold to lift.
        for (link in targets) if (!deferIfHeld(link, subscription)) sendRequest(link, subscription)
    }

    /** Sends [subscription]'s REQ to [link], from that relay's own read cursor when it has one. */
    private fun sendRequest(link: RelayLink, subscription: PoolSubscription) {
        if (!link.isOpen) return
        link.sendIfOpen(RelayCodec.requestFrame(subscription.id, subscription.requestFilters(link.url, now() / 1_000)))
    }

    /** Under no lock. True when [link]'s relay is held, in which case [subscription] is sent when it lifts. */
    private fun deferIfHeld(link: RelayLink, subscription: PoolSubscription): Boolean {
        if (!gate.isHeld(link.url, now())) {
            synchronized(lock) { subscription.deferredOn -= link.url }
            return false
        }
        synchronized(lock) { subscription.deferredOn += link.url }
        scheduleLift(link)
        return true
    }

    /**
     * Waits out [link]'s hold, then sends what it held back: the subscriptions
     * deferred for it and the writes queued for it. A socket that dropped in
     * the meantime needs neither: reopening re-sends both.
     */
    private fun scheduleLift(link: RelayLink) = synchronized(lock) {
        if (!started || links[link.url] !== link || link.liftJob?.isActive == true) return@synchronized
        link.liftJob = scope.launch {
            while (true) {
                val wait = gate.heldFor(link.url, now())
                if (wait <= 0) break
                // Jittered, so every pool on this phone does not resume in the same instant.
                delay(wait + random.nextLong(HOLD_JITTER_MS + 1))
            }
            if (!link.isOpen) return@launch
            val deferred = synchronized(lock) {
                subscriptions.values.filter { link.url in it.deferredOn }.onEach { it.deferredOn -= link.url }
            }
            if (link.url in readRelays) for (subscription in deferred) {
                if (link.url in subscription.stoppedOn || subscription.only != null && link.url !in subscription.only) continue
                sendRequest(link, subscription)
            }
            if (link.url in writeRelays) link.flushOutbox(now())
        }
    }

    /** A refusal [link]'s relay gave, by what it asks for. */
    private fun refused(link: RelayLink, kind: RelayRefusalKind?, subscriptionId: String? = null, eventId: String? = null) {
        when (kind) {
            RelayRefusalKind.RATE_LIMITED -> {
                gate.rateLimited(link.url, now())
                val subscription = subscriptionId?.let { id -> synchronized(lock) { subscriptions[id] } }
                if (subscription != null) synchronized(lock) { subscription.deferredOn += link.url }
                scheduleLift(link)
            }
            RelayRefusalKind.STOP -> {
                // Not sent again on reconnect, nor offered again, until this pool is
                // rebuilt: a new pool is what a change of relays or sign-in makes.
                subscriptionId?.let { id -> synchronized(lock) { subscriptions[id]?.stoppedOn?.add(link.url) } }
                eventId?.let { gate.stopEvent(link.url, it) }
            }
            null -> Unit
        }
    }

    private fun close(subscription: PoolSubscription) {
        subscription.stop()
        val targets = subscriptionTargets(subscription)
        synchronized(lock) { subscriptions.remove(subscription.id) }
        forgetRead(subscription.id)
        val frame = RelayCodec.closeFrame(subscription.id)
        for (link in targets) link.sendIfOpen(frame)
    }

    private fun deliver(url: String, subscriptionId: String, event: NostrEvent) {
        val subscription = synchronized(lock) { subscriptions[subscriptionId] } ?: return
        // Before de-duplication: what this relay holds moves its own cursor,
        // whichever relay delivered the event first.
        subscription.delivered(url, event.createdAt, now() / 1_000)
        subscription.offer(event)
    }

    private fun subscriptionEose(url: String, subscriptionId: String) {
        val subscription = synchronized(lock) { subscriptions[subscriptionId] } ?: return
        subscription.endOfStored(url)
        subscription.eose(url)
        subscription.onEose(url)
    }

    private fun onLinkOpen(link: RelayLink, observed: RelayAttemptTrace?) {
        observed?.record(RelayTraceEvent.LINK_OPEN_ENTER)
        try {
            val live: List<PoolSubscription>
            synchronized(lock) { live = subscriptions.values.toList() }
            // Subscriptions do not survive a dropped socket, so re-send every live
            // REQ before anything else. Skipping this is how a client silently goes
            // deaf after a relay restart while still looking connected. Filters are
            // re-evaluated here, not read from a stored frame, so a subscription
            // that advances its own `since` as events arrive re-REQs from where it
            // left off rather than replaying everything since the original send.
            // A subscription the relay refused for good is not asked again, and one
            // it is holding off waits for the hold to lift.
            if (link.url in readRelays) for (subscription in live) {
                if (subscription.only != null && link.url !in subscription.only) continue
                if (synchronized(lock) { link.url in subscription.stoppedOn }) continue
                if (deferIfHeld(link, subscription)) continue
                sendRequest(link, subscription)
            }
            if (link.url in writeRelays) { if (gate.isHeld(link.url, now())) scheduleLift(link) else link.flushOutbox(now()) }
            health(link.url) { it.copy(connection = "Connected") }
            val published = synchronized(lock) { links.values.filter { it.isOpen }.map { it.url }.toSet() }
            _connected.value = published
            observed?.record(RelayTraceEvent.CONNECTED_PUBLISHED, trace?.nextRevision() ?: 0, published.size.toLong())
            observed?.record(RelayTraceEvent.LINK_OPEN_COMPLETE)
        } catch (error: Throwable) {
            observed?.record(RelayTraceEvent.LINK_OPEN_FAILED, relayTraceFailure(error).ordinal.toLong())
            throw error
        }
    }

    /**
     * Ask a relay that has just said OK to a stored kind for that event back, once per kind per
     * connection: a relay that accepts chat and keeps none of it looks healthy by every other
     * measure. Only a real EOSE without the event counts against it; a timeout, a refusal or a
     * dropped socket proves nothing, and the kind is tried again on a later write.
     *
     * [force] asks about this one event whatever was seen of its kind: a write again, or a caller
     * still waiting for an answer about it ([askBack]). A relay that never said OK to the event is
     * only asked where it is: what it answers says nothing about the relay, which never took it.
     */
    private fun scheduleReadBack(link: RelayLink, eventId: String, force: Boolean = false, immediate: Boolean = false) {
        val generation = link.socketGeneration
        val own = "${link.url}\n$eventId"
        val kind = synchronized(lock) {
            val kind = writeKinds[eventId] ?: acknowledged[eventId]?.kind ?: return
            if (!isStoredKind(kind) || link.url !in readRelays || own in askedBack) return
            if (!force && !readBackChecked.getOrPut(link.url) { mutableSetOf() }.add(kind)) return
            askedBack += own
            kind
        }
        scope.launch {
            try {
                if (!immediate) delay(READ_BACK_DELAY_MS)
                val returned = if (link.isOpen && link.socketGeneration == generation) readBackOnce(link, eventId) else null
                if (returned == null) { if (!force) synchronized(lock) { readBackChecked[link.url]?.remove(kind) }; return@launch }
                val accountable = synchronized(lock) {
                    val ours = acknowledged[eventId]?.relays?.get(link.url)
                    ours?.found = returned
                    // Only a relay that said OK is held to account, or credited, for the kind.
                    val accountable = ours?.acked != false
                    if (accountable && returned && link.socketGeneration == generation) kept.getOrPut(link.url) { mutableSetOf() }.add(kind)
                    accountable
                }
                if (accountable) health(link.url) { it.copy(unreturned = if (returned) it.unreturned - kind else it.unreturned + kind) }
            } finally {
                synchronized(lock) { askedBack -= own }
            }
        }
    }

    /**
     * What is known about the relays [eventId] was offered to, or null when this pool wrote no
     * such event of a stored kind since it started. Cheap and in memory: a caller that shows
     * delivery asks again as it redraws. A relay that refused the event is left out.
     */
    fun publishEvidence(eventId: String): PublishEvidence? = synchronized(lock) {
        val record = acknowledged[eventId] ?: return null
        val health = _health.value
        val answering = record.relays.filterValues { !it.refused }
        PublishEvidence(record.kind, answering.map { (url, ack) ->
            RelayAckEvidence(url, readable = url in readRelays, found = ack.found, acked = ack.acked, at = ack.at,
                keeps = kept[url]?.contains(record.kind) == true,
                lost = health[url]?.unreturned?.contains(record.kind) == true)
        }, pending = if (eventId in publications) answering.values.count { !it.acked && it.found == null } else 0)
    }

    /** Ask each relay [eventId] went to and that has not answered about it for it back, by name, now:
     *  for a caller that has waited for the one check per kind and still has no answer about this event. */
    fun askBack(eventId: String) {
        val targets = synchronized(lock) {
            val record = acknowledged[eventId] ?: return
            record.relays.filterValues { it.found == null && !it.refused }.keys.mapNotNull { links[it] }
        }
        targets.forEach { scheduleReadBack(it, eventId, force = true, immediate = true) }
    }

    private suspend fun readBackOnce(link: RelayLink, eventId: String): Boolean? {
        if (gate.isHeld(link.url, now())) return null
        val id = "km-readback-${nextSubscriptionId.incrementAndGet()}"
        val readBack = ReadBack(link.url, eventId)
        synchronized(lock) { readBacks[id] = readBack }
        return try {
            link.sendIfOpen(RelayCodec.requestFrame(id, listOf(Filter(ids = listOf(eventId)))))
            withTimeoutOrNull(READ_BACK_TIMEOUT_MS) { readBack.result.await() }
        } finally {
            synchronized(lock) { readBacks.remove(id) }
            forgetRead(id)
            link.sendIfOpen(RelayCodec.closeFrame(id))
        }
    }

    private class ReadBack(val url: String, val eventId: String) {
        @Volatile var found = false
        val result = CompletableDeferred<Boolean?>()
    }

    private fun onLinkClosed(link: RelayLink, observed: RelayAttemptTrace?) {
        synchronized(lock) { readBackChecked.remove(link.url); kept.remove(link.url); readBacks.values.filter { it.url == link.url }.forEach { it.result.complete(null) } }
        // Every live REQ is sent again on reopening; what it said before is kept on show until then.
        synchronized(lock) { refusedReads.remove(link.url) }
        synchronized(lock) { storedQueries.values.forEach { it.failed(link.url) } }
        synchronized(lock) { negentropyQueries.values.filter { it.url == link.url }.forEach { it.fail("NIP-77 relay closed") } }
        synchronized(lock) { nip77FetchQueries.values.filter { it.url == link.url }.forEach { it.fail("NIP-77 relay closed") } }
        synchronized(lock) { nip77OfferPublications.values.filter { it.url == link.url }.toSet().forEach { it.fail("NIP-77 relay closed") } }
        val published = synchronized(lock) { links.values.filter { it.isOpen }.map { it.url }.toSet() }
        _connected.value = published
        observed?.record(RelayTraceEvent.CONNECTED_PUBLISHED, trace?.nextRevision() ?: 0, published.size.toLong())
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
            link.isOpen = false
            _connected.value = links.values.filter { it.isOpen }.map { it.url }.toSet()
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
        var unopened = 0
        while (isActive) {
            // A relay that has asked this phone to slow down, through any pool, is
            // not dialled until its hold lifts.
            val held = gate.heldFor(link.url, now())
            if (held > 0) {
                health(link.url) { it.copy(connection = "Relay asked to slow down; waiting") }
                delay(held + random.nextLong(HOLD_JITTER_MS + 1))
                continue
            }
            health(link.url) { it.copy(connection = if (attempt == 0) "Connecting" else "Reconnecting") }
            val closed = CompletableDeferred<String>()
            link.closed = closed
            var connectedAt: Long? = null
            // CONNECTING until the socket opens or the pool gives up on it.
            // Whichever comes first wins; the loser is ignored.
            val phase = AtomicInteger(CONNECTING)
            val observed = trace?.attempt(link.traceIndex, link.traceAttempts.incrementAndGet())
            if (observed != null) link.traceActive = TraceLinkAttempt(observed, phase)
            observed?.record(RelayTraceEvent.ATTEMPT_START)
            var socket: RelaySocket? = null
            val listener = object : RelaySocketListener {
                override val attemptTrace = observed
                override fun onOpen() {
                    val accepted = phase.compareAndSet(CONNECTING, OPEN)
                    // CAS result is direct; phase is a subsequent observation.
                    observed?.record(RelayTraceEvent.OPEN_DECISION, if (accepted) 1 else 0, phase.get().toLong())
                    if (!accepted) {
                        runCatching { socket?.close() }
                        return
                    }
                    connectedAt = now()
                    val hasAuthenticator = authenticators.forUrl(link.url) != null
                    val requiresAuth = hasAuthenticator && !(publicAuthOnChallenge && !LinkRelayAddress.looksLikeLink(link.url) && link.url !in circle())
                    synchronized(lock) {
                        link.socketGeneration += 1
                        link.authState = if (hasAuthenticator) AuthState.AWAITING_CHALLENGE else AuthState.READY
                        link.isOpen = !requiresAuth
                    }
                    if (!requiresAuth) onLinkOpen(link, observed)
                }

                override fun onMessage(text: String) {
                    if (phase.get() == ABANDONED) return
                    val message = RelayCodec.parse(text)
                    if (message is RelayMessage.Auth) beginAuthentication(link, message.challenge)
                    if (message is RelayMessage.Ok && authenticationOk(link, message)) onLinkOpen(link, observed)
                    storedMessage(link.url, message)
                    negentropyMessage(link.url, message)
                    nip77FetchMessage(link.url, message)
                    when (message) {
                        is RelayMessage.Event -> if (link.url in readRelays) deliver(link.url, message.subscriptionId, message.event)
                        is RelayMessage.EndOfStoredEvents -> {
                            readAnswered(link.url, message.subscriptionId, null)
                            subscriptionEose(link.url, message.subscriptionId)
                        }
                        is RelayMessage.Closed -> {
                            readAnswered(link.url, message.subscriptionId,
                                if (RelayRefusal.strip(message.message).startsWith("auth-required:")) "Authentication required" else READ_REFUSED)
                            refused(link, RelayRefusal.classify(message.message, authenticators.forUrl(link.url) != null),
                                subscriptionId = message.subscriptionId)
                        }
                        is RelayMessage.Ok -> if (link.url in writeRelays && synchronized(lock) { message.eventId in attemptedWrites }) {
                            health(link.url) { it.copy(write = if (message.accepted) "Write accepted" else writeRefusal(message.message)) }
                            val again = noteAnswer(link.url, message.eventId, message.accepted)
                            if (message.accepted) scheduleReadBack(link, message.eventId, force = again)
                            else refused(link, RelayRefusal.classify(message.message, authenticators.forUrl(link.url) != null),
                                eventId = message.eventId)
                        }
                        is RelayMessage.Notice -> if (RelayRefusal.noticeAsksToSlowDown(message.message)) {
                            health(link.url) { it.copy(connection = "Connected; relay asked to slow down") }
                            refused(link, RelayRefusalKind.RATE_LIMITED)
                        }
                        is RelayMessage.Auth -> if (authenticators.forUrl(link.url) == null) health(link.url) { it.copy(read = "Relay requests authentication") }
                        // Everything else is informational. A CLOSED from one
                        // relay does not end the subscription: the others are
                        // still carrying it.
                        else -> Unit
                    }
                }

                override fun onClosed(reason: String) {
                    // Already written off and already being retried.
                    val abandoned = phase.get() == ABANDONED
                    observed?.record(RelayTraceEvent.CALLBACK_CLOSED, if (abandoned) 1 else 0)
                    if (abandoned) return
                    link.authJob?.cancel()
                    link.socketGeneration += 1
                    link.isOpen = false
                    link.socket = null
                    if (link.authState != AuthState.BLOCKED) link.authState = AuthState.CLOSED
                    // A refused upgrade (429, 503, 403) holds the relay for every pool on this phone.
                    RelayRefusal.httpStatus(reason)?.let { status -> gate.upgradeRefused(link.url, status, now()) }
                    onLinkClosed(link, observed)
                    health(link.url) { it.copy(connection = "Disconnected; retrying") }
                    closed.complete(reason)
                }
            }

            socket = runCatching { sockets.open(link.url, listener) }.onFailure { error ->
                observed?.record(RelayTraceEvent.SOCKET_OPEN_FAILED, relayTraceFailure(error).ordinal.toLong())
            }.getOrNull()
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
                    val abandoned = phase.compareAndSet(CONNECTING, ABANDONED)
                    observed?.record(RelayTraceEvent.WATCHDOG_DECISION, if (abandoned) 1 else 0, phase.get().toLong())
                    if (!abandoned) return@launch
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
            unopened = if (connectedAt == null) unopened + 1 else 0
            // Full jitter above a floor: a uniform draw from zero to the backoff
            // ceiling, so a relay restart does not bring every device in the room
            // back at the same instant, and never sooner than the floor.
            val retryMs = policy.retryDelay(attempt, unopened) { bound -> random.nextLong(bound) }
            observed?.record(RelayTraceEvent.RETRY_SCHEDULED, retryMs)
            delay(retryMs)
            observed?.record(RelayTraceEvent.RETRY_STARTED)
        }
    }.also { job ->
        if (trace != null) job.invokeOnCompletion { link.traceActive?.context?.record(RelayTraceEvent.ATTEMPT_LOOP_END) }
    }

    /** Sampled independently of app locks; not an atomic lifecycle snapshot. */
    fun traceSnapshot() {
        val observer = trace ?: return
        observer.record(RelayTraceEvent.STATE_SNAPSHOT, observer.revision(), _connected.value.size.toLong())
        for (link in traceLinks) {
            val active = link.traceActive ?: continue
            active.context.record(RelayTraceEvent.LINK_SNAPSHOT,
                active.phase.get().toLong(), if (link.isOpen) 1 else 0)
        }
    }

    private data class TraceLinkAttempt(val context: RelayAttemptTrace, val phase: AtomicInteger)

    private companion object {
        const val CONNECTING = 0
        const val OPEN = 1
        const val ABANDONED = 2
    }

    private inner class RelayLink(val url: String) {
        val traceIndex = trace?.nextLink() ?: 0
        val traceAttempts = AtomicLong()
        @Volatile var traceActive: TraceLinkAttempt? = null
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
        /** Waiting out a hold on this relay; see [scheduleLift]. */
        @Volatile var liftJob: Job? = null

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
        fun sendOrQueue(frame: String, event: NostrEvent) {
            if (url !in writeRelays) return
            if (gate.eventStopped(url, event.id)) return
            if (gate.isHeld(url, now())) {
                // Held: a heartbeat or a call signal is worthless by the time the hold
                // lifts, so it is dropped; anything else waits for the lift.
                if (isEphemeralKind(event.kind)) return
                synchronized(outboxLock) {
                    outbox.addLast(Pending(frame, now(), held = true))
                    while (outbox.size > policy.outboxLimit) outbox.removeFirst()
                }
                scheduleLift(this)
                return
            }
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
                // Held writes were kept back on purpose and wait out the hold, still
                // bounded by the outbox limit.
                ready = outbox.filter { it.held || at - it.queuedAt <= policy.outboxTtlMs }
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

    private class Pending(val frame: String, val queuedAt: Long, val held: Boolean = false)

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

    /** Strict unless [partial]: then a relay that sends CLOSED or drops is set aside, not fatal. */
    private class StoredQuery(private val targets: Set<String>, private val partial: Boolean = false) {
        val result = CompletableDeferred<List<NostrEvent>>()
        val firstEnd = CompletableDeferred<Unit>()
        private val ended = mutableSetOf<String>()
        private val gone = linkedMapOf<String, Exception>()
        private val events = linkedMapOf<String, NostrEvent>()
        private var bytes = 0L
        fun event(url: String, event: NostrEvent) {
            if (url !in targets || url in ended || url in gone || result.isCompleted) return
            if (event.id in events) return
            if (!dev.forgesworn.kithmoot.protocol.Events.verify(event)) return
            bytes += event.toJson().toString().length * 2L
            if (events.size >= 2_048 || bytes > 4 * 1024 * 1024) {
                result.completeExceptionally(IllegalStateException("Stored relay query exceeded its limit"))
            } else events[event.id] = event
        }
        fun end(url: String) {
            if (url in targets && url !in gone) { ended.add(url); firstEnd.complete(Unit) }
            if (ended.containsAll(targets)) result.complete(events.values.toList())
            else if (partial && (ended + gone.keys).containsAll(targets)) finish()
        }
        fun failed(url: String) = lost(url, IllegalStateException("Stored relay query was interrupted"))
        fun refused(url: String, reason: String) = lost(url, RelayHistoryException(url, reason.contains("auth-required:", ignoreCase = true)))
        private fun lost(url: String, error: Exception) {
            if (url !in targets || url in ended) return
            if (!partial) { result.completeExceptionally(error); return }
            gone.putIfAbsent(url, error)
            if ((ended + gone.keys).containsAll(targets)) finish()
        }
        /** Best effort: settle with what arrived, failing only when no relay answered. */
        fun finish() {
            if (ended.isNotEmpty() || events.isNotEmpty()) result.complete(events.values.toList())
            else result.completeExceptionally(gone.values.firstOrNull() ?: IllegalStateException("No relay answered the stored query"))
        }
    }

    /**
     * One subscription across the whole pool.
     *
     * The [SeenEvents] here is what makes "publish everywhere" survivable: the
     * same event arrives once per relay, and the room must see it once.
     */
    private data class SubscriptionItem(val event: NostrEvent? = null, val eose: String? = null)
    private class SubscriptionOverflow : Exception()

    private class PoolSubscription(
        val id: String,
        val filters: () -> List<Filter>,
        val only: Set<String>?,
        val onEose: (url: String) -> Unit,
        val keeperRequestClock: (() -> Long)? = null,
        private val seen: SeenEvents = SeenEvents(),
        /** Set for [subscribeCatchingUp]: re-REQs narrow to each relay's cursor less this. */
        private val catchUpSkewSeconds: Long? = null,
    ) {
        /**
         * Per relay, the newest `created_at` it delivered, clamped to this
         * phone's clock, counted only once that relay has sent EOSE for a REQ:
         * a socket that drops mid-replay may have sent the newest stored events
         * and not yet the older ones. Our own publications never arrive as EVENT
         * frames, so an unacknowledged send cannot move a cursor.
         */
        private val readTo = HashMap<String, Long>()
        /** Relays whose current REQ has not reached EOSE, with the newest seen so far. */
        private val replaying = HashMap<String, Long?>()

        /** The filters for a REQ to [url] now, which also starts that relay's read afresh. */
        @Synchronized
        fun requestFilters(url: String, nowSeconds: Long): List<Filter> {
            val base = filters()
            val skew = catchUpSkewSeconds ?: return base
            replaying[url] = null
            val cursor = readTo[url] ?: return base
            val from = (cursor - skew).coerceAtMost(nowSeconds)
            return base.flatMap { filter ->
                if (filter.since != null && filter.since >= from) listOf(filter)
                // The gap since the drop, and the full filter for live delivery
                // only: `limit` bounds a relay's stored replay, not what it pushes
                // afterwards, so an event published late is not filtered out by
                // the narrowed `since` for the rest of this connection.
                else listOf(filter.copy(since = from), filter.copy(limit = 1))
            }
        }

        @Synchronized
        fun delivered(url: String, createdAt: Long, nowSeconds: Long) {
            if (catchUpSkewSeconds == null) return
            val at = minOf(createdAt, nowSeconds)
            if (url in replaying) replaying[url] = maxOf(replaying[url] ?: at, at)
            else readTo[url] = maxOf(readTo[url] ?: at, at)
        }

        @Synchronized
        fun endOfStored(url: String) {
            if (catchUpSkewSeconds == null || url !in replaying) return
            replaying.remove(url)?.let { newest -> readTo[url] = maxOf(readTo[url] ?: newest, newest) }
        }

        /** Relays that refused this subscription for good (`blocked:`, `restricted:`, `pow:`,
         *  `auth-required:` with no grant): it is not sent to them again. Under the pool's lock. */
        val stoppedOn = mutableSetOf<String>()
        /** Relays holding this phone off that this subscription waits on. Under the pool's lock. */
        val deferredOn = mutableSetOf<String>()
        private val controlSeen = linkedMapOf<String, Pair<Int, Long>>()
        private var controlHigh = 0L
        private val _events = MutableSharedFlow<SubscriptionItem>(replay = 0, extraBufferCapacity = SUBSCRIPTION_BUFFER_CAPACITY)
        val events: SharedFlow<SubscriptionItem> = _events.asSharedFlow()
        @Volatile var lostEvents = false
            private set
        private var queued = 0
        private var stopped = false

        @Synchronized
        fun offer(event: NostrEvent) {
            if (stopped) return
            if (keeperRequestClock != null && event.kind !in setOf(20466, 20468)) return
            if (keeperRequestClock != null && event.kind in setOf(20466, 20468)) {
                val accepted = synchronized(controlSeen) {
                    controlHigh = maxOf(controlHigh, keeperRequestClock.invoke())
                    val old = controlSeen[event.id]
                    if (old != null && (old.first >= 3 || controlHigh - old.second < 1_000)) false
                    else {
                        controlSeen[event.id] = (old?.first?.plus(1) ?: 1) to controlHigh
                        while (controlSeen.size > 4096) controlSeen.remove(controlSeen.keys.first())
                        true
                    }
                }
                if (!accepted) return
            } else {
                if (seen.contains(event.id)) return
                // Do not evict successful IDs for an input that cannot fit.
                if (queued >= SUBSCRIPTION_BUFFER_CAPACITY) { lostEvents = true; return }
                if (!seen.admit(event.id)) return
            }
            queued++
            if (!_events.tryEmit(SubscriptionItem(event = event))) {
                queued--
                lostEvents = true
                if (keeperRequestClock == null) seen.forget(event.id)
            }
        }

        @Synchronized
        fun eose(url: String) {
            if (stopped) return
            queued++
            if (!_events.tryEmit(SubscriptionItem(eose = url))) { queued--; lostEvents = true }
        }

        @Synchronized
        fun consumed(recover: Boolean): Boolean {
            queued--
            if (!recover || queued != 0 || !lostEvents) return false
            // Fence late socket offers before detaching this collector;
            // otherwise an undelivered late event could poison shared seen IDs.
            stopped = true
            return true
        }

        @Synchronized
        fun stop() { stopped = true }
    }
}

private const val SUBSCRIPTION_BUFFER_CAPACITY = 256
/** Spread added to the end of a hold, so the pools on one phone do not all resume at once. */
private const val HOLD_JITTER_MS = 5_000L

/** NIP-01 ephemeral kinds: presence, call signals, recovery asks. Not worth holding for later. */
internal fun isEphemeralKind(kind: Int): Boolean = kind in 20_000 until 30_000
// At most 4096 successful admissions across an initial burst and its retries.
private const val MAX_SUBSCRIPTION_RECOVERIES = 15

private const val READ_CONFIRMED = "History read confirmed"
private const val READ_REFUSED = "Read refused"

/** A fixed description of a relay's `OK false`, by its NIP-01 prefix, as the web client words it.
 *  The relay's own words stay out of health, which Settings shows. */
internal fun writeRefusal(raw: String): String = RelayRefusal.strip(raw).let { reason -> when {
    reason.startsWith("auth-required:") -> "Write refused: authentication required"
    reason.startsWith("rate-limited:") -> "Write refused: rate limited"
    reason.startsWith("pow:") -> "Write refused: proof of work required"
    reason.startsWith("blocked:") || reason.startsWith("restricted:") -> "Write refused: not accepted from this account or kind"
    reason.startsWith("invalid:") -> "Write refused as invalid"
    else -> "Write refused"
} }

/** How long after a relay accepts a stored kind it is asked for it back, and how long it has to answer. */
private const val READ_BACK_DELAY_MS = 3_000L
private const val READ_BACK_TIMEOUT_MS = 8_000L
/** Written events the pool remembers the relays of, as the web client does. */
private const val MAX_ACKNOWLEDGED = 1_000

/** Regular kinds (NIP-01), which a relay that keeps anything keeps. */
private fun isStoredKind(kind: Int): Boolean = kind == 1 || kind == 2 || kind in 4 until 45 || kind in 1_000 until 10_000
