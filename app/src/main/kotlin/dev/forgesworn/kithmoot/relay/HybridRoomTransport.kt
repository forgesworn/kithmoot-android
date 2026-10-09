package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.atomic.AtomicLong

/** Participant-owned fanout over two explicitly authorised lanes. Receiving
 * somebody else's event never forwards it. The caller owns lane lifecycles. */
class HybridRoomTransport(private val nearby: RoomTransport, private val internet: RoomTransport,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) : RoomTransport {
    private val lock = Any()
    private val generation = AtomicLong()
    @Volatile private var blocked = false
    @Volatile private var resetReady = true
    private val relayObserved = linkedSetOf<String>()
    private data class Seen(val offers: Int, val at: Long)

    private fun observed(event: NostrEvent, expected: Long = generation.get()) = synchronized(lock) {
        if (blocked || generation.get() != expected) return@synchronized
        relayObserved.add(event.id)
        while (relayObserved.size > 2048) relayObserved.remove(relayObserved.first())
    }

    override fun publish(event: NostrEvent) = synchronized(lock) {
        check(!blocked) { "Room paths are awaiting rekey" }
        offerBoth(event, recovery = false)
    }

    private fun offerBoth(event: NostrEvent, recovery: Boolean) {
        var accepted = false
        var failure: Exception? = null
        for (lane in listOf(nearby, internet)) try {
            if (recovery) lane.publishRecovery(event) else lane.publish(event)
            accepted = true
        } catch (error: Exception) { failure = error }
        if (!accepted) throw checkNotNull(failure)
    }

    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean =
        publishConfirmedGuarded(event, generation.get(), { true }, timeoutMs)

    override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        val offered = synchronized(lock) {
            if (blocked || this.generation.get() != generation || !stillAllowed()) return false
            try { nearby.publish(event); true } catch (_: Exception) { false }
        }
        val accepted = try {
            internet.publishConfirmedGuarded(event, internet.publicationGeneration(),
                { !blocked && this.generation.get() == generation && stillAllowed() }, timeoutMs)
        } catch (cancel: CancellationException) { throw cancel
        } catch (error: Exception) {
            if (offered) throw PublicationUnconfirmedException()
            throw error
        }
        if (!accepted && offered) throw PublicationUnconfirmedException()
        return accepted
    }

    override fun receivedEventConfirmsPublication(eventId: String): Boolean =
        synchronized(lock) { eventId in relayObserved } && internet.receivedEventConfirmsPublication(eventId)
    override fun receivedViaRelays(eventId: String): List<String> =
        if (synchronized(lock) { eventId in relayObserved }) internet.receivedViaRelays(eventId) else emptyList()
    override fun publicationGeneration(): Long = generation.get()
    override fun reachable(): Boolean = !blocked && (nearby.reachable() || internet.reachable())
    override fun describe(): List<String> = internet.describe()
    override fun circleRelays(): Set<String> = internet.circleRelays()

    // Complete-history authority remains the explicitly selected relay lane.
    // A nearby cache or timeout can never supply EOSE on its behalf.
    override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
        val expected = generation.get()
        return internet.queryStored(filters, timeoutMs).also { events -> events.forEach { observed(it, expected) } }
    }

    override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> = supervisorScope {
        val expected = generation.get()
        val local = async { nearby.queryAvailable(filters, timeoutMs) }
        val remote = async { internet.queryAvailable(filters, timeoutMs).also { events -> events.forEach { observed(it, expected) } } }
        suspend fun available(job: Deferred<List<NostrEvent>>) = try { job.await() }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                emptyList()
            }
        (available(remote) + available(local)).distinctBy { it.id }.take(128)
    }

    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = combined(filters, null)
    override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> =
        combined(filters, onReplayComplete)

    private fun combined(filters: List<Filter>, replayed: (() -> Unit)?): Flow<NostrEvent> = channelFlow {
        val seen = linkedMapOf<String, Seen>()
        val seenLock = Any()
        suspend fun receive(event: NostrEvent, relay: Boolean) {
            // Record provenance even if another lane supplied this ID first.
            if (relay) observed(event)
            val first = synchronized(seenLock) {
                val at = nowSeconds()
                val previous = seen[event.id]
                // A lost answer needs an identical signed request retry. Keep
                // immediate cross-lane copies and ordinary chat deduplicated.
                if (previous != null && (event.kind !in setOf(20466, 20467, 20468, 20469) ||
                        previous.offers >= 3 || at <= previous.at)) return@synchronized false
                seen[event.id] = Seen((previous?.offers ?: 0) + 1, at)
                while (seen.size > 2048) seen.remove(seen.keys.first())
                true
            }
            if (first) send(event)
        }
        supervisorScope {
            launch {
                try { nearby.subscribe(filters).collect { receive(it, false) } }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Other selected lane remains live. */ }
            }
            launch {
                try {
                    val source = if (replayed == null) internet.subscribe(filters) else internet.subscribeReplayed(filters, replayed)
                    source.collect { receive(it, true) }
                } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Other selected lane remains live. */ }
            }
        }
    }

    override suspend fun beginRekey() {
        val resetGeneration = synchronized(lock) {
            blocked = true; resetReady = false; relayObserved.clear(); generation.incrementAndGet()
        }
        // Both lanes must be stopped, even if one fails. Do not hold the room
        // lock while the native lane awaits Android's main thread.
        withContext(NonCancellable) {
            try { internet.beginRekey() } finally { nearby.beginRekey() }
        }
        synchronized(lock) { if (generation.get() == resetGeneration) resetReady = true }
    }
    override suspend fun rekey(roomKey: ByteArray) {
        val resetGeneration = synchronized(lock) {
            check(blocked && resetReady)
            resetReady = false
            generation.get()
        }
        withContext(NonCancellable) {
            try { internet.rekey(roomKey) } finally { nearby.rekey(roomKey) }
        }
        synchronized(lock) {
            if (generation.get() == resetGeneration) { relayObserved.clear(); resetReady = true }
        }
    }
    override fun completeRekey() = synchronized(lock) {
        check(blocked && resetReady)
        internet.completeRekey(); nearby.completeRekey(); blocked = false
    }
    override fun keepPast(roomKeys: List<ByteArray>) { internet.keepPast(roomKeys); nearby.keepPast(roomKeys) }
    override fun publishRecovery(event: NostrEvent) = synchronized(lock) {
        check(resetReady)
        require(event.kind in setOf(20468, 20469, 20471, 20472)) { "Not an epoch recovery event" }
        offerBoth(event, recovery = true)
    }
}
