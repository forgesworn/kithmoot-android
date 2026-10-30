package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.callrelay.BuiltInRelayPairing
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.SpryControlEndpoint
import dev.forgesworn.kithmoot.protocol.SpryKey
import dev.forgesworn.kithmoot.protocol.SpryLink
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.protocol.standard
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One room owns its Spry grant, credentials and timers. Closing a room cancels
 * every pending request. The web's `app/src/spry-room.ts`, request for request.
 *
 * A paired device always holds its own grant in the room and registers its
 * own key, so its own calls use its own relay everywhere. It shares that
 * relay (publishes the offer and registers the other members' keys) only in
 * a room it made ([madeHere]) or where the person chose to on this device
 * ([SpryShareStore]). Every device redeems the offer another member shared,
 * and vouches for devices admitted after it while its redemption is fresh.
 *
 * Another member's `spry:` offer is redeemed only from a standard node (a
 * built-in relay). Any other member's offer, `spry:` or `https:`, is
 * republished only as a marked copy (`relayed`), and only while this device
 * may use it; with Hide my IP address on ([relayOnly]), neither redeemed
 * nor applied until the person chose to use that member's relay
 * ([allowHost]). Own relays are never asked about.
 *
 * It also answers a host who asks this device to test that host's built-in
 * relay from outside ([RelayOutsideTests]): this phone is a tester only.
 */
internal class SpryRoomAccess(context: Context, private val session: RoomSession, private val parent: CoroutineScope, private val account: String?,
    /** This device made the room: it holds the root inviter key itself, with no delegation. */
    private val madeHere: Boolean,
    /** Whether calls here are relay-only (Hide my IP address), read each time it matters. */
    private val relayOnly: () -> Boolean = { false },
    /**
     * This device's own built-in relay for an account, while it runs: used as
     * this device's pairing when no pairing was claimed by hand, exactly as a
     * claimed one is, but never saved or synced. Read when the room opens; the
     * app rebuilds this object when it starts or stops.
     */
    private val builtIn: (String?) -> BuiltInRelayPairing? = { null },
    private val apply: (CallRelayConfig?) -> Unit) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Mutex()
    private val key = SpryKey.secret(session.identity.deviceSecretKey)
    private val spryPublic = session.spryPublicKey
    private val participant = session.identity.participant
    private val roomId = session.room.roomId
    private val store = SpryPairingStore(context)
    private val offers = SpryOfferStore(context)
    private val shares = SpryShareStore(context)
    private val consents = SpryConsentStore(context)
    private val saved = scope.async(Dispatchers.IO) { offers.read(participant, roomId) }
    /** A pairing claimed by hand wins; otherwise this device's own running relay. */
    private val pairing = scope.async(Dispatchers.IO) { store.read(account) ?: builtIn(account)?.pairing }
    private val chosen = scope.async(Dispatchers.IO) { runCatching { shares.read(participant, roomId) }.getOrDefault(false) }
    /** Hosts whose own call relay (built-in or shared over https) this person chose to use with Hide my IP address on, read once. */
    private val consented = scope.async(Dispatchers.IO) { runCatching { consents.hosts(participant) }.getOrDefault(emptySet()).toMutableSet() }
    private val peerRevision = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private val registrar = SpryRegistrar(spryPublic, participant)
    private val voucher = SpryVoucher(session.identity.devicePubkey, spryPublic)
    /** Whether this device shares its relay here; null until the choice is read. */
    private var share: Boolean? = null
    private val _shareChoice = MutableStateFlow<Boolean?>(null)
    /** The "Share my call relay here" switch: null where it is not offered
     *  (unpaired, or a room this device made), else whether it is on. */
    val shareChoice: StateFlow<Boolean?> = _shareChoice.asStateFlow()
    /** Tokens this device withdrew; never republished or redeemed again. */
    private val withdrawn = mutableSetOf<String>()
    /** Bumped when the grant this device holds is dropped, so a request in flight cannot bring it back. */
    private var generation = 0
    private var own: SpryOffer? = null
    /** The offer this device republishes and redeems: its own grant, or the one another member shared. */
    private var retained: SpryOffer? = null
    /** The newest offer the room's descriptors carry. */
    private var shared: SpryOffer? = null
    private val credentials = LinkedHashMap<String, SpryCallCredential>()
    private var applied: CallRelayConfig? = null
    private var credentialUntil = 0L
    private var lastPublication = 0L
    private var publication: Job? = null
    private var registration: Job? = null
    private var retryJob: Job? = null
    private var windowStart = 0L
    private var retryStep = 0
    /** Status of the last refused redemption of another member's relay. */
    private var sharedRefusal = 0
    private val appContext = context.applicationContext
    /** Answers a host's relay outside test; cancelled with this room's job. */
    private val outsideTests = RelayOutsideTests(
        session = object : RelayTestSession {
            override val device = session.identity.devicePubkey
            override val relayTests = session.relayTests
            override fun sendRelayTest(toDevice: String, type: String, relayTest: kotlinx.serialization.json.JsonObject) =
                session.sendRelayTest(toDevice, type, relayTest)
        },
        relay = object : RelayTestRelay {
            override suspend fun testOffer(endpoint: String) = this@SpryRoomAccess.testOffer(endpoint)
            override suspend fun testCredential(endpoint: String) = this@SpryRoomAccess.testCredential(endpoint)
        },
        parent = scope,
        run = { login -> RelayOutsideTestRun.run(appContext, login) },
    )
    init {
        scope.launch {
            try {
                val pair = pairing.await()
                retained = retained ?: saved.await()?.also { windowStart = System.currentTimeMillis() }
                shares()
                if (pair != null) refresh() else publish()
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Call controls show a missing relay if needed. */ }
        }
        scope.launch { session.participants.map { it.flatMap { person -> person.devices.map { entry -> entry.device + ":" + entry.sid } }.sorted() }.distinctUntilChanged().collect {
            scheduleVouches()
            // A registrar adds newcomers' keys and drops removed participants'.
            if (pairing.await() != null && own != null && registration?.isActive != true) registration = scope.launch {
                delay(1000)
                refreshQuietly()
            }
            if (publishable() != null && publication?.isActive != true) publication = scope.launch {
                delay(maxOf(0, 1000 - (System.currentTimeMillis() - lastPublication)))
                publish()
            }
        } }
        scope.launch { session.spryOffer.collect { next ->
            if (next == null) return@collect
            val pair = pairing.await()
            // Private adverts travel to our paired node; they never open foreign
            // sockets. Another member's standard node (a built-in relay) is the
            // one foreign `spry:` offer a device redeems.
            if (spryForeignControl(next, pair?.endpoint) && !next.standard) return@collect
            if (next.token in withdrawn) return@collect
            val fresh = shared?.token != next.token
            shared = next
            if (fresh) { windowStart = System.currentTimeMillis(); retryStep = 0 }
            if (pair != null && own != null) return@collect
            val old = retained
            val kept = if (old?.token == next.token && old.endpoint == next.endpoint) next.copy(expiresAt = maxOf(next.expiresAt, old.expiresAt)) else next
            retained = kept
            withContext(Dispatchers.IO) { offers.save(participant, roomId, kept) }
            if (session.currentCall() != null) refreshQuietly()
        } }
        scope.launch { session.spryPeers.collect {
            if (pairing.await()?.endpoint?.startsWith("spry:") == true) refreshQuietly()
        } }
        scope.launch {
            while (isActive) {
                delay(30_000)
                applyCredentials()
                if (pairing.await() != null || session.currentCall() != null) refreshQuietly()
            }
        }
    }
    /** This device's own built-in relay's local listener, read at each
     *  credential request, when [pair] is that relay's pairing. */
    private fun localTurn(pair: SpryPairing?): dev.forgesworn.kithmoot.callrelay.LocalTurn? =
        builtIn(account)?.takeIf { pair != null && it.pairing.endpoint == pair.endpoint && it.pairing.id == pair.id }?.local

    /** Whether this account has a Spry pairing; with one, no public call relay is looked up or logged in to. */
    suspend fun paired(): Boolean = pairing.await() != null

    /** Whether this device shares its own relay in this room. */
    private suspend fun shares(): Boolean = share ?: (madeHere || chosen.await()).also { on ->
        share = on
        if (pairing.await() != null && !madeHere) _shareChoice.value = on
    }

    private suspend fun ownNode(offer: SpryOffer): Boolean = pairing.await()?.endpoint == offer.endpoint

    /** The participant running this offer's relay, as the room's descriptors show
     *  it: the one unmarked publisher, never a copy. */
    private fun hostOf(offer: SpryOffer): String? = runCatching { spryOfferHost(session.currentSpryPeers(), offer.endpoint, offer.token) }.getOrNull()

    /** Whether this device may use the member relay [host] offers now. */
    private suspend fun mayUse(host: String?): Boolean {
        val chosen = consented.await()
        return memberRelayAllowed(relayOnly(), participant, host, synchronized(chosen) { chosen.toSet() })
    }

    /** Another member's offer is used only while this device may use that
     *  member's relay, and a `spry:` one only from a standard node. */
    private suspend fun usable(offer: SpryOffer, ownEndpoint: String?): Boolean {
        val allowed = !spryMemberOffer(offer, ownEndpoint) || mayUse(hostOf(offer))
        return spryOfferUsable(offer, ownEndpoint) { allowed }
    }

    /**
     * Another member's own relay (built-in or shared over https) this device
     * holds an offer for but may not use yet, and who offers it (null when no single participant does):
     * what a one-time choice would name. The web's `SpryRoom.blocked()`.
     */
    suspend fun blocked(): BlockedMemberRelay? {
        val own = pairing.await()?.endpoint
        val now = System.currentTimeMillis() / 1000
        for (offer in listOfNotNull(retained, shared)) {
            if (offer.expiresAt <= now || offer.token in withdrawn) continue
            if (spryMemberOffer(offer, own) && (!spryForeignControl(offer, own) || offer.standard) && !usable(offer, own)) return BlockedMemberRelay(offer.endpoint, hostOf(offer))
        }
        return null
    }

    /** The standard offer this device holds for [endpoint], unwithdrawn and unexpired. */
    private fun heldStandard(endpoint: String): SpryOffer? {
        val now = System.currentTimeMillis() / 1000
        return listOfNotNull(shared, retained, own).firstOrNull { offer ->
            offer.endpoint == endpoint && offer.expiresAt > now && offer.token !in withdrawn && offer.standard
        }
    }

    /**
     * For a relay outside test: who offers the built-in relay at [endpoint] in
     * this room (null when no single participant does) and whether this
     * device may use it now. Hide my IP address is read from the stored
     * choice, never asked here. Null when this device holds no unexpired
     * standard offer for it. The web's `SpryRoom.testOffer`.
     */
    suspend fun testOffer(endpoint: String): RelayTestOffer? {
        if (!job.isActive) return null
        val offer = heldStandard(endpoint) ?: return null
        return RelayTestOffer(hostOf(offer), usable(offer, pairing.await()?.endpoint))
    }

    /**
     * A relay login for a relay outside test of the built-in relay at
     * [endpoint]: one this device already holds while it has a minute left,
     * or a fresh redemption. Never kept, applied to this room's calls or
     * counted as a redemption for vouching. Throws when there is no usable
     * offer or the node refuses. The web's `SpryRoom.testCredential`.
     */
    suspend fun testCredential(endpoint: String): CallRelayConfig {
        val pair = pairing.await()
        val offer = heldStandard(endpoint)
        if (!job.isActive || offer == null || !usable(offer, pair?.endpoint)) throw SpryRequestException(403, SHARED_RELAY_UNAVAILABLE)
        val held = synchronized(credentials) { credentials[offer.endpoint + ":" + offer.token] }
        if (held != null && held.expiresAt - System.currentTimeMillis() / 1000 > 60) return held.relay
        return SpryClient.credentials(offer, key, pair).relay
    }

    /** The person chose, on this device, to use [host]'s relay with Hide my IP
     *  address on. Kept on this device; throws when it could not be kept. */
    suspend fun allowHost(host: String) {
        withContext(Dispatchers.IO) { consents.save(participant, host) }
        val chosen = consented.await()
        synchronized(chosen) { chosen += host }
    }

    /** The offer this device republishes: its own grant when it shares, or the
     *  one another member shared (as a marked copy), never a grant it withdrew
     *  or keeps to itself. */
    private suspend fun publishable(): SpryOffer? {
        val sharing = shares()
        val offer = (if (sharing) own ?: retained else retained) ?: return null
        if (offer.token in withdrawn) return null
        val pair = pairing.await()?.endpoint
        // Another member's offer, over `spry:` or `https:`, is kept in the room
        // as a marked copy, so its host stays the only unmarked publisher and
        // consent can name them. Only an offer this device may use is copied.
        if (spryMemberOffer(offer, pair)) return spryRepublication(offer, pair, usable(offer, pair))
        if (!sharing && ownNode(offer) && offer.token != shared?.token) return null
        return spryRepublication(offer, pair, true)
    }

    private suspend fun publish() {
        val offer = publishable() ?: return
        if (offer.expiresAt <= System.currentTimeMillis() / 1000) return
        lastPublication = System.currentTimeMillis()
        try { session.publishSpryOffer(offer) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Republished on the next roster change. */ }
    }

    private suspend fun refreshQuietly() {
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Retried at the bounded renewal interval. */ }
    }

    /**
     * Ready the room's relay before a call. [patient] is for a call that may
     * not go direct (Hide my IP address): a refusal of another member's relay
     * is retried at 3 and 10 seconds first, since the owner's app registers a
     * newcomer's key only once it has seen it join.
     */
    suspend fun prepare(patient: Boolean = false): CallRelayConfig? {
        windowStart = System.currentTimeMillis(); retryStep = 0
        if (pairing.await() == null && session.spryOffer.value == null && retained == null && saved.await() == null)
            withTimeoutOrNull(2500) { session.spryOffer.filterNotNull().first() }
        var step = 0
        while (true) {
            try { refresh(); break } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                if (credentialUntil > System.currentTimeMillis() / 1000) break
                if (!patient || sharedRefusal != 403 || step >= 2) throw error
                retryJob?.cancel(); retryJob = null
                retryStep = step + 1
                delay(SPRY_RETRY_MS[step])
                step++
            }
        }
        applyCredentials()
        return applied
    }

    /** The relay a call uses: this device's own grant first, then the one it
     *  holds, then the one shared; any other still valid carries a call over a gap. */
    private fun applyCredentials() = synchronized(credentials) {
        val now = System.currentTimeMillis() / 1000
        credentials.entries.removeAll { it.value.expiresAt <= now }
        credentialUntil = credentials.values.maxOfOrNull { it.expiresAt } ?: 0
        val order = listOfNotNull(own, retained, shared).map { it.endpoint + ":" + it.token }
        val best = order.firstNotNullOfOrNull { credentials[it] } ?: credentials.values.firstOrNull()
        val relay = best?.relay
        if (relay != applied) { applied = relay; apply(relay) }
    }

    /** The registrar's pass for this device's own grant: its own key always,
     *  members' keys where it shares, removals and the seal each on their own. */
    private suspend fun registerDevices() {
        val pair = pairing.await() ?: return
        val offer = own ?: return
        registrar.register(offer.token, session.spryRoster(), session.removedParticipants(), shares()) { change, revision ->
            SpryClient.registerDevices(pair, offer, change, revision)
        }
    }

    private fun scheduleVouches() {
        if (registrar.unsupported()) return
        for (due in voucher.due(listOf(shared, retained), own?.token, session.spryRoster())) scope.launch {
            delay(due.delayMs)
            try { SpryClient.vouch(due.offer, key, listOf(due.key)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SpryRequestException) { if (error.status == 404) registrar.markUnsupported() }
            catch (_: Exception) { /* Each newcomer is vouched for once; the owner's app registers them anyway. */ }
        }
    }

    /** After a refusal in the first minute of an offer or a call: 3, 10 and 30
     *  seconds, then the ordinary 30-second timer. */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true || retryStep >= SPRY_RETRY_MS.size) return
        if (System.currentTimeMillis() - windowStart > SPRY_RETRY_WINDOW_MS) return
        val wait = SPRY_RETRY_MS[retryStep++]
        retryJob = scope.launch { delay(wait); refreshQuietly() }
    }

    private suspend fun refresh() = lock.withLock {
        currentCoroutineContext().ensureActive()
        val revision = generation
        val now = System.currentTimeMillis() / 1000
        val pair = pairing.await()
        var failure: Exception? = null
        var ownFailure: Exception? = null
        sharedRefusal = 0
        if (pair != null && (own?.expiresAt ?: 0) - now < 360) {
            try {
                val fresh = SpryClient.grant(pair, roomId)
                currentCoroutineContext().ensureActive()
                if (revision != generation) return@withLock
                own = fresh
                retained = fresh
                withContext(Dispatchers.IO) { offers.save(participant, roomId, fresh) }
                publish()
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = error; ownFailure = error }
        }
        // Registered before the first redemption, so a node that seals every
        // grant still serves this device from its first call.
        try { registerDevices() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Asked again on the next pass. */ }
        if (revision != generation) return@withLock
        val seen = mutableSetOf<String>()
        for (offer in listOfNotNull(own, retained, shared)) {
            if (offer.expiresAt <= now || !usable(offer, pair?.endpoint)) continue
            if (offer.token in withdrawn) continue
            val id = offer.endpoint + ":" + offer.token
            if (!seen.add(id)) continue
            if ((synchronized(credentials) { credentials[id] }?.expiresAt ?: 0) - now > 90) continue
            val mine = own?.token == offer.token && own?.endpoint == offer.endpoint
            try {
                val fresh = SpryClient.credentials(offer, key, pair, localTurn(pair))
                currentCoroutineContext().ensureActive()
                if (revision != generation) return@withLock
                synchronized(credentials) { credentials[id] = fresh }
                voucher.redeemed(offer)
                val renewed = offer.copy(expiresAt = fresh.accessExpiresAt, node = fresh.node ?: offer.node)
                if (mine) {
                    val changed = own?.node?.id != renewed.node?.id
                    own = renewed
                    if (changed) publish()
                }
                if (own == null || mine) retained = renewed
                withContext(Dispatchers.IO) { offers.save(participant, roomId, renewed) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                failure = error
                if (mine) {
                    ownFailure = error
                    // The grant was removed or lapsed on the node: ask again next time.
                    if (error is SpryRequestException && error.status == 403) own = null
                } else if (error is SpryRequestException && error.status == 403) {
                    sharedRefusal = 403
                    scheduleRetry()
                }
            }
        }
        applyCredentials()
        scheduleVouches()
        // A standard node (the built-in relay) has no private mesh to tell.
        if (pair?.endpoint?.startsWith("spry:") == true && own?.standard != true) {
            val ownKey = SpryControlEndpoint.parse(pair.endpoint).publicKey
            val peers = session.currentSpryPeers().mapNotNull { SpryLink.card(it.offer.node?.toJson(), now) }
                .filter { it.pubkey != ownKey }.groupBy { it.pubkey }.mapValues { (_, cards) -> cards.maxWith(compareBy({ it.createdAt }, { it.id })) }
                .toSortedMap().values.take(8)
            try { SpryClient.peers(pair, roomId, key.toHex(), peers, peerRevision.incrementAndGet()) }
            catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = error }
        }
        // This device's own pairing speaks first: its hint is the useful one.
        val error = ownFailure ?: failure
        if (error != null && credentialUntil <= now) throw error
    }

    /**
     * Start or stop sharing this device's relay in this room. Starting
     * publishes the grant this device already holds and registers members.
     * Stopping stops publishing it, asks the node to drop that grant, so
     * access already handed out ends within five minutes, and takes a fresh
     * grant, never published, for this device's own calls; credentials it
     * already holds carry its call over the gap. False only when the removal
     * could not be confirmed.
     */
    suspend fun setShare(on: Boolean): Boolean {
        if (on == shares()) return true
        share = on
        if (pairing.await() != null && !madeHere) _shareChoice.value = on
        if (on) {
            scope.launch { refreshQuietly(); publish() }
            return true
        }
        val pair = pairing.await()
        val mine = own ?: retained?.takeIf { pair != null && it.endpoint == pair.endpoint }
        generation++
        own = null
        registrar.reset()
        if (mine != null) withdrawn += mine.token
        retained = shared?.takeIf { it.token !in withdrawn }
        var removed = true
        if (pair != null && mine != null) {
            try { SpryClient.removeGrant(pair, mine) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { removed = error is SpryRequestException && error.status == 403 }
        }
        scope.launch { refreshQuietly() }
        return removed
    }

    fun close() {
        outsideTests.close()
        job.cancel()
        val device = key.toHex()
        key.fill(0)
        // A standard node (the built-in relay) was never told of peers.
        val standardNode = own?.standard == true
        parent.launch(Dispatchers.IO) {
            try {
                pairing.await()?.takeIf { it.endpoint.startsWith("spry:") && !standardNode }?.let { SpryClient.peers(it, roomId, device, emptyList(), peerRevision.incrementAndGet()) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Expiring peer cards bound an offline withdrawal. */ }
        }
    }
}
