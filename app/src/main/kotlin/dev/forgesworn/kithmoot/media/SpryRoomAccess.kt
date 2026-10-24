package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.SpryControlEndpoint
import dev.forgesworn.kithmoot.protocol.SpryLink
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One room owns its renewal jobs. Closing a room cancels every pending request. */
internal class SpryRoomAccess(context: Context, private val session: RoomSession, private val parent: CoroutineScope, account: String?,
    private val apply: (CallRelayConfig?) -> Unit) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Mutex()
    private val key = Digests.hmacSha256(session.identity.deviceSecretKey, "spry-device-v1".toByteArray(Charsets.UTF_8))
    private val store = SpryPairingStore(context)
    private val offers = SpryOfferStore(context)
    private val saved = scope.async(Dispatchers.IO) { offers.read(session.identity.participant, session.room.roomId) }
    private val pairing = scope.async(Dispatchers.IO) { store.read(account) }
    private val peerRevision = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private var retained: SpryOffer? = null
    private var own: SpryOffer? = null
    private var credential: CallRelayConfig? = null
    private var credentialUntil = 0L
    private var lastPublication = 0L
    private var publication: Job? = null
    init {
        scope.launch {
            try {
                if (pairing.await() != null) refresh()
                else saved.await()?.let { session.publishSpryOffer(it) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Call controls show a missing relay if needed. */ }
        }
        scope.launch { session.participants.map { it.flatMap { person -> person.devices.map { entry -> entry.device + ":" + entry.sid } }.sorted() }.distinctUntilChanged().collect {
            (own ?: retained ?: session.spryOffer.value ?: saved.await())?.let { offer ->
                if (publication?.isActive != true) publication = scope.launch {
                    delay(maxOf(0, 1000 - (System.currentTimeMillis() - lastPublication)))
                    lastPublication = System.currentTimeMillis()
                    session.publishSpryOffer(own ?: offer)
                }
            }
        } }
        scope.launch { session.spryOffer.collect {
            val pair = pairing.await()
            val usable = it?.takeIf { offer -> !offer.endpoint.startsWith("spry:") || offer.endpoint == pair?.endpoint }
            if (usable != null) {
                val it = usable
                val old = retained
                retained = if (old?.token == it.token && old.endpoint == it.endpoint) it.copy(expiresAt = maxOf(it.expiresAt, old.expiresAt)) else it
                withContext(Dispatchers.IO) { offers.save(session.identity.participant, session.room.roomId, requireNotNull(retained)) }
            }
            if (it != null && pairing.await() == null && session.currentCall() != null) {
                try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Retry at the bounded renewal interval. */ }
            }
        } }
        scope.launch { session.spryPeers.collect {
            if (pairing.await()?.endpoint?.startsWith("spry:") == true) {
                try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        } }
        scope.launch {
            while (isActive) {
                delay(30_000)
                if (credentialUntil > 0 && credentialUntil <= System.currentTimeMillis() / 1000) { credential = null; credentialUntil = 0; apply(null) }
                try { if (pairing.await() != null || session.currentCall() != null) refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Last good access remains usable only until its expiry. */ }
            }
        }
    }
    suspend fun prepare(): CallRelayConfig? {
        if (pairing.await() == null && session.spryOffer.value == null && saved.await() == null) withTimeoutOrNull(2500) { session.spryOffer.filterNotNull().first() }
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            if (credentialUntil <= System.currentTimeMillis() / 1000) throw error
        }
        return credential?.takeIf { credentialUntil > System.currentTimeMillis() / 1000 }
    }
    private suspend fun refresh() = lock.withLock {
        currentCoroutineContext().ensureActive()
        val now = System.currentTimeMillis() / 1000
        val pair = pairing.await()
        var failure: Exception? = null
        if (pair != null && (own?.expiresAt ?: 0) - now < 360) {
            try {
                val fresh = SpryClient.grant(pair, session.room.roomId)
                currentCoroutineContext().ensureActive()
                own = fresh
                withContext(Dispatchers.IO) { offers.save(session.identity.participant, session.room.roomId, fresh) }
                lastPublication = System.currentTimeMillis()
                session.publishSpryOffer(fresh)
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = error }
        }
        if (credentialUntil - now <= 90) {
            val seen = mutableSetOf<String>()
            for (offer in listOfNotNull(own, retained, session.spryOffer.value, saved.await())) {
                if (offer.expiresAt <= now || offer.endpoint.startsWith("spry:") && offer.endpoint != pair?.endpoint || !seen.add(offer.endpoint + ":" + offer.token)) continue
                try {
                    val fresh = SpryClient.credentials(offer, key, pair)
                    currentCoroutineContext().ensureActive()
                    credential = fresh.relay; credentialUntil = fresh.expiresAt
                    val renewed = offer.copy(expiresAt = fresh.accessExpiresAt, node = fresh.node ?: offer.node)
                    retained = renewed
                    withContext(Dispatchers.IO) { offers.save(session.identity.participant, session.room.roomId, renewed) }
                    if (own?.endpoint == offer.endpoint && own?.token == offer.token) {
                        val changed = own?.node?.id != renewed.node?.id
                        own = renewed
                        if (changed) session.publishSpryOffer(renewed)
                    }
                    apply(fresh.relay)
                    break
                } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = error }
            }
        }
        if (pair?.endpoint?.startsWith("spry:") == true) {
            val ownKey = SpryControlEndpoint.parse(pair.endpoint).publicKey
            val peers = session.currentSpryPeers().mapNotNull { SpryLink.card(it.offer.node?.toJson(), now) }
                .filter { it.pubkey != ownKey }.groupBy { it.pubkey }.mapValues { (_, cards) -> cards.maxWith(compareBy({ it.createdAt }, { it.id })) }
                .toSortedMap().values.take(8)
            try { SpryClient.peers(pair, session.room.roomId, key.toHex(), peers, peerRevision.incrementAndGet()) }
            catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure = error }
        }
        if (failure != null && credentialUntil <= now) throw failure
    }

    fun close() {
        job.cancel()
        val device = key.toHex()
        key.fill(0)
        parent.launch(Dispatchers.IO) {
            try {
                pairing.await()?.takeIf { it.endpoint.startsWith("spry:") }?.let { SpryClient.peers(it, session.room.roomId, device, emptyList(), peerRevision.incrementAndGet()) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Expiring peer cards bound an offline withdrawal. */ }
        }
    }
}
