package dev.forgesworn.kithmoot.media

import android.content.Context
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
internal class SpryRoomAccess(context: Context, private val session: RoomSession, parent: CoroutineScope, account: String?,
    private val apply: (CallRelayConfig?) -> Unit) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Mutex()
    private val key = Digests.hmacSha256(session.identity.deviceSecretKey, "spry-device-v1".toByteArray(Charsets.UTF_8))
    private val store = SpryPairingStore(context)
    private val offers = SpryOfferStore(context)
    private val saved = scope.async(Dispatchers.IO) { offers.read(session.identity.participant, session.room.roomId) }
    private val pairing = scope.async(Dispatchers.IO) { store.read(account) }
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
        scope.launch { session.participants.map { it.flatMap { person -> person.devices }.sorted() }.distinctUntilChanged().collect {
            (own ?: retained ?: session.spryOffer.value ?: saved.await())?.let { offer ->
                if (publication?.isActive != true) publication = scope.launch {
                    delay(maxOf(0, 1000 - (System.currentTimeMillis() - lastPublication)))
                    lastPublication = System.currentTimeMillis()
                    session.publishSpryOffer(own ?: offer)
                }
            }
        } }
        scope.launch { session.spryOffer.collect {
            if (it != null) {
                val old = retained
                retained = if (old?.token == it.token && old.endpoint == it.endpoint) it.copy(expiresAt = maxOf(it.expiresAt, old.expiresAt)) else it
                withContext(Dispatchers.IO) { offers.save(session.identity.participant, session.room.roomId, requireNotNull(retained)) }
            }
            if (it != null && pairing.await() == null && session.currentCall() != null) {
                try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Retry at the bounded renewal interval. */ }
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
        if (pair != null && (own?.expiresAt ?: 0) - now < 360) {
            val fresh = SpryClient.grant(pair, session.room.roomId)
            currentCoroutineContext().ensureActive()
            own = fresh
            withContext(Dispatchers.IO) { offers.save(session.identity.participant, session.room.roomId, fresh) }
            lastPublication = System.currentTimeMillis()
            session.publishSpryOffer(fresh)
        }
        val offer = own ?: retained ?: session.spryOffer.value ?: saved.await() ?: return@withLock
        if (offer.expiresAt <= now || credentialUntil - now > 90) return@withLock
        val fresh = SpryClient.credentials(offer, key)
        currentCoroutineContext().ensureActive()
        credential = fresh.relay; credentialUntil = fresh.expiresAt
        val renewed = offer.copy(expiresAt = fresh.accessExpiresAt)
        retained = renewed
        withContext(Dispatchers.IO) { offers.save(session.identity.participant, session.room.roomId, renewed) }
        if (own != null) own = renewed
        apply(fresh.relay)
    }
    fun close() { job.cancel(); key.fill(0) }
}
