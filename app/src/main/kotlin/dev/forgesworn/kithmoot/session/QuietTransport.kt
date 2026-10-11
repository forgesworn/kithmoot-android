package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.DeadDrop
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.QuietKeys
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
import dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * A quiet room's transport: the chosen kinds ride the kind 1059 stream as
 * room drops on a cadence, and come back by broadcast; everything else
 * passes straight through. The room case of `nostr-deaddrop`'s quiet
 * transport, written from its README, over the app's own [RoomTransport].
 *
 * What a relay sees: one wrap per slot from this device whether or not
 * anybody spoke, each to a key it has never seen, at a fresh random moment
 * inside each slot, and a pull of every wrap since the lookback. Live
 * traffic (roster, signalling, pairing) stays in the open, because a slot
 * of delay would end a call; what quiet hides is what was said, by whom
 * and when. The web client draws the same keys from the same room key, so
 * a room is quiet from either end.
 *
 * Two devices of one person draw from disjoint halves of the member's
 * keys: the device holding the identity takes the first, the device it
 * paired the second. A third reads and cannot post.
 */
class QuietTransport(
    private val inner: RoomTransport,
    roomKey: ByteArray,
    member: String,
    members: Collection<String>,
    /** 0 for the device holding the identity, 1 for the device it paired; anything else reads only. */
    slot: Int,
    private val scope: CoroutineScope,
    private val quietKinds: Set<Int> = setOf(KIND_CHAT),
    private val intervalSeconds: Long = SLOT_SECONDS,
    private val bucket: Int = BUCKET_BYTES,
    lookbackSeconds: Long = HISTORY_SECONDS,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: (Int) -> ByteArray = Entropy::bytes,
    /** What was kept on the device: the counters spent this epoch and the events still waiting. */
    restore: QuietState? = null,
    /** Called whenever a counter is spent or the queue changes, with what to keep. */
    private val onState: (QuietState) -> Unit = {},
    /** Old-key inner events returned to the conversation when an epoch retires. */
    private val onRekeyed: (List<NostrEvent>) -> Unit = {},
    /** Start the slot timer. Tests drive [tick] by hand. */
    ticking: Boolean = true,
    /** Where inside each slot this device posts, in seconds from the slot's
     *  start. Drawn fresh per slot by default so posting times carry no fixed
     *  phase a relay could link across circuits. Tests pass `{ 0 }`. */
    private val slotOffset: ((Long) -> Long)? = null,
    /** Counters delegated durably to a box. A complete device range means the
     * box owns this phone's cadence for that epoch, so the phone emits no
     * competing filler or real wrap. */
    private val reservedCounters: (Long) -> Set<Int> = { emptySet() },
) : RoomTransport {

    companion object {
        const val SLOT_SECONDS: Long = 300
        const val BUCKET_BYTES: Int = 4096
        const val HISTORY_SECONDS: Long = 2L * 24 * 3600
        const val DEVICE_SLOTS: Int = 2
        const val MAX_PENDING: Int = 256
        const val CANNOT_SEND: String = "This device cannot post in a quiet room. Two devices per person can: the one holding your identity and the one it paired. Others read."
        const val TOO_LONG: String = "This message is too long for a quiet room. Every message in a quiet room is padded to one size, and this one does not fit."
        private val EVENT_ID = Regex("[0-9a-f]{64}")
        const val MEANING: String = "Quiet room. Messages ride the gift-wrap stream as dead drops, one every five minutes at most, and a relay cannot tell whether anything was said, by whom, or when. Being here still shows while you are here, and calls are not quiet."

        fun counterRange(slot: Int): IntRange? {
            if (slot < 0 || slot >= DEVICE_SLOTS) return null
            val width = DeadDrop.MAX_PER_EPOCH_ROOM / DEVICE_SLOTS
            return (slot * width) until ((slot + 1) * width)
        }

        fun fingerprintFor(roomKey: ByteArray): String {
            require(roomKey.size == 32)
            return Digests.sha256(roomKey).toHex()
        }
    }

    /** What survives a restart. */
    class QuietState(
        val used: Map<String, QuietKeys.UsedCounters>,
        val queued: List<NostrEvent>,
        val boxPending: Set<String> = emptySet(),
        val keyFingerprint: String? = null,
        /** Conservatively occupied before dispatch; restart waits for a later slot. */
        val offeredThroughSlot: Long = -1,
    )

    private val member = member.lowercase()
    private val members = members.map(String::lowercase).distinct()
    private val range = counterRange(slot)
    val canSend: Boolean = range != null && members.any { it.equals(member, ignoreCase = true) }

    private val lookbackEpochs = ((lookbackSeconds + DeadDrop.EPOCH_SECONDS - 1) / DeadDrop.EPOCH_SECONDS).toInt()
    private val keys = QuietKeys(lookbackEpochs = lookbackEpochs)
    private var roomKey = roomKey.copyOf()
    /**
     * Epochs this room has left, by key fingerprint, newest first: lookup
     * only, so a drop a member sealed before following a rekey, or one a
     * relay delivers late, still opens. Never sent under, and their counters
     * are never marked. At most [MAX_PAST_EPOCHS]. Under its own monitor.
     */
    private val past = LinkedHashMap<String, Pair<ByteArray, QuietKeys>>()
    private var pastBackfill: Job? = null
    private val lock = Mutex()
    private val queue = ArrayDeque<NostrEvent>()
    private val boxPending = mutableSetOf<String>()
    private class GuardedPublication(val event: NostrEvent, val generation: Long,
        val innerGeneration: Long, val stillAllowed: () -> Boolean) {
        val receipt = CompletableDeferred<Boolean>()
        @Volatile var attempted = false
    }
    private data class SlotPublication(val slot: Long, val wrap: NostrEvent,
        val event: NostrEvent?, val guard: GuardedPublication? = null,
        var countersSaved: Boolean = false)
    // These requests are owned by the caller's recording journal/draft, not
    // the ordinary quiet queue. Restoring the queue must never replay them.
    private val guarded = linkedMapOf<String, GuardedPublication>()
    private val confirmedGuarded = linkedSetOf<String>()
    private val generation = AtomicLong()
    @Volatile private var stopped = false
    private var current: SlotPublication? = null
    private var lastSlot = -1L
    private var offeredThroughSlot = -1L
    private var offsetSlot = -1L
    private var offset = 0L
    private val delivered = LinkedHashSet<String>()
    private val opened = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 512)
    private var broadcast: Job? = null
    private var timer: Job? = null
    @Volatile private var publicationBlocked = false
    @Volatile private var keyFingerprint = fingerprintFor(roomKey)

    init {
        require(intervalSeconds > 0) { "intervalSeconds must be positive" }
        keys.set(DeadDrop.roomIkm(roomKey), this.members)
        keys.refresh(now())
        if (restore != null) {
            require(restore.offeredThroughSlot >= -1) { "invalid quiet slot marker" }
            offeredThroughSlot = restore.offeredThroughSlot
            lastSlot = offeredThroughSlot
            require(restore.keyFingerprint == null || restore.keyFingerprint == keyFingerprint) {
                "quiet state belongs to an earlier room epoch"
            }
            keys.importUsed(restore.used, now())
            for (e in restore.queued) if (e.kind in quietKinds && queue.size < MAX_PENDING) queue.addLast(e)
            val retainedIds = queue.mapTo(mutableSetOf()) { it.id }
            require(restore.boxPending.size <= MAX_PENDING && restore.boxPending.all { EVENT_ID.matches(it) && it in retainedIds }) {
                "invalid box-pending quiet queue"
            }
            boxPending += restore.boxPending
        }
        if (ticking) timer = scope.launch {
            var first = true
            while (isActive) {
                // A fixed polling phase can miss an offset after the last
                // poll of every slot. Wake at its deadline, checking the
                // clock at least every thirty seconds. A refused publish
                // retries after one second, using tick's retained wrap.
                val waitMs = lock.withLock {
                    val t = now()
                    val slot = slotIndex(t)
                    val at = if (slot <= lastSlot) (slot + 1) * intervalSeconds
                        else slot * intervalSeconds + offsetFor(slot)
                    if (at > t) minOf(30L, at - t) * 1000
                    else if (first) 1L else 1000L
                }
                delay(waitMs)
                first = false
                runCatching { tick() }
            }
        }
    }

    val pending: Int get() = synchronized(queue) { queue.size }

    /** Exact inner events retained on this device until their delegated box
     * queue receipt has also been saved. */
    fun queuedEvents(): List<NostrEvent> = synchronized(queue) { queue.filterNot { it.id in guarded } }

    /** Remove a box-confirmed inner event only after the smaller durable state
     * has been written. A failed write leaves the event available for retry. */
    fun confirmQueued(eventId: String): Boolean = synchronized(queue) {
        if (eventId in guarded) return@synchronized false
        if (queue.none { it.id == eventId }) return@synchronized false
        val retained = queue.filterNot { it.id == eventId || it.id in guarded }
        onState(QuietState(keys.exportUsed(), retained, boxPending - eventId, keyFingerprint, offeredThroughSlot))
        queue.removeAll { it.id == eventId }
        boxPending.remove(eventId)
        true
    }

    fun stop() {
        stopped = true
        generation.incrementAndGet()
        synchronized(queue) { guarded.values.toList().forEach(::rejectGuarded); confirmedGuarded.clear() }
        timer?.cancel(); timer = null
        broadcast?.cancel(); broadcast = null
        pastBackfill?.cancel(); pastBackfill = null
    }

    override fun describe(): List<String> = inner.describe()
    override fun circleRelays(): Set<String> = inner.circleRelays()

    override suspend fun beginRekey() {
        publicationBlocked = true
        generation.incrementAndGet()
        synchronized(queue) { guarded.values.toList().forEach(::rejectGuarded); confirmedGuarded.clear() }
        inner.beginRekey()
        lock.withLock { /* wait for an in-flight slot to finish or fail */ }
    }

    override suspend fun rekey(roomKey: ByteArray) {
        require(roomKey.size == 32) { "room key must be 32 bytes" }
        check(publicationBlocked) { "room publication must be blocked before rekey" }
        val nextFingerprint = fingerprintFor(roomKey)
        val rejected = lock.withLock {
            synchronized(queue) {
                val old = queue.toList()
                onState(QuietState(emptyMap(), emptyList(), emptySet(), nextFingerprint, offeredThroughSlot))
                queue.clear()
                boxPending.clear()
                lastSlot = maxOf(lastSlot, offeredThroughSlot)
                current = null
                val left = this.roomKey
                keys.set(DeadDrop.roomIkm(roomKey), members)
                keyFingerprint = nextFingerprint
                this.roomKey = roomKey.copyOf()
                // The epoch just left stays readable until the session says otherwise.
                synchronized(past) { keepPastLocked(listOf(left) + past.values.map { it.first }) }
                old
            }
        }
        inner.rekey(roomKey)
        if (rejected.isNotEmpty()) onRekeyed(rejected)
    }

    override fun keepPast(roomKeys: List<ByteArray>) {
        val added = synchronized(past) { keepPastLocked(roomKeys) }
        // Drops under a key this device never held were dropped unread when
        // they first arrived; read the stored stream again for them.
        if (added && broadcast != null) {
            val since = maxOf(0L, now() - HISTORY_SECONDS - RoomDrops.CREATED_AT_JITTER)
            pastBackfill?.cancel()
            pastBackfill = scope.launch { backfill(since) }
        }
    }

    /** Replace the kept epochs with [roomKeys], newest first; true when one is new. */
    private fun keepPastLocked(roomKeys: List<ByteArray>): Boolean {
        val next = LinkedHashMap<String, Pair<ByteArray, QuietKeys>>()
        var added = false
        for (key in roomKeys) {
            require(key.size == 32) { "room key must be 32 bytes" }
            val fingerprint = fingerprintFor(key)
            if (fingerprint == keyFingerprint || fingerprint in next) continue
            if (next.size == MAX_PAST_EPOCHS) break
            next[fingerprint] = past[fingerprint] ?: Pair(key.copyOf(), QuietKeys(lookbackEpochs = lookbackEpochs).also {
                it.set(DeadDrop.roomIkm(key), members)
                it.refresh(now())
                added = true
            })
        }
        past.clear()
        past.putAll(next)
        return added
    }

    private fun lookupPast(tag: String, t: Long): QuietKeys.Hit? = synchronized(past) {
        past.values.firstNotNullOfOrNull { (_, kept) -> kept.refresh(t); kept.lookup(tag) }
    }

    override fun completeRekey() {
        check(publicationBlocked) { "room rekey is not in progress" }
        inner.completeRekey()
        publicationBlocked = false
    }
    override fun publishRecovery(event: NostrEvent) = inner.publishRecovery(event)

    /**
     * A quiet kind is queued for the next slot; an event too large for the
     * bucket is refused here, to the caller, not discovered at its slot.
     * Anything else goes straight to the relays.
     */
    override fun receivedEventConfirmsPublication(eventId: String): Boolean =
        inner.receivedEventConfirmsPublication(eventId)
    override fun receivedViaRelays(eventId: String): List<String> = inner.receivedViaRelays(eventId)

    override fun publish(event: NostrEvent) = retain(event, forBox = false)

    /** Retain a delegated event across process death and keep the phone timer
     * from taking it after the lease boundary while its box receipt is unknown. */
    fun retainForBox(event: NostrEvent) = retain(event, forBox = true)

    private fun retain(event: NostrEvent, forBox: Boolean) {
        check(!stopped && !publicationBlocked) { "Room publication is closed or blocked during a secure update" }
        if (event.kind !in quietKinds) return inner.publish(event)
        check(canSend) { CANNOT_SEND }
        try { RoomDrops.plaintext(event, bucket) } catch (_: RoomDrops.RumorTooLarge) { throw IllegalArgumentException(TOO_LONG) }
        synchronized(queue) {
            check(!stopped && !publicationBlocked) { "Room publication is closed or blocked during a secure update" }
            check(event.id !in guarded) { "This event already has a guarded publication owner" }
            val added = queue.none { it.id == event.id }
            if (added) check(queue.size < MAX_PENDING) { "quiet queue is full; the relay has not taken a slot in a long time" }
            val marked = forBox && event.id !in boxPending
            if (added) queue.addLast(event)
            if (marked) boxPending += event.id
            if (added || marked) {
                try {
                    onState(state())
                } catch (error: Exception) {
                    if (added) queue.removeAll { it.id == event.id }
                    if (marked) boxPending.remove(event.id)
                    throw error
                }
            }
        }
    }

    /** A quiet send is confirmed when its exact inner event is durably queued;
     * relay delivery happens at the fixed slot and survives a restart. */
    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
        publish(event)
        return true
    }

    override fun publicationGeneration(): Long = generation.get()

    /** Unlike ordinary chat's queue receipt, this waits for the exact inner
     * event's wrap to be accepted. No guarded request survives cancellation,
     * timeout, stop or restart; its caller retains the retry authority. */
    override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        require(timeoutMs > 0)
        if (stopped || publicationBlocked || generation != this.generation.get() || !stillAllowed())
            throw PublicationNotOfferedException()
        val innerGeneration = inner.publicationGeneration()
        if (event.kind !in quietKinds) return inner.publishConfirmedGuarded(event, innerGeneration,
            { !stopped && !publicationBlocked && generation == this.generation.get() && stillAllowed() }, timeoutMs)
        check(canSend) { CANNOT_SEND }
        try { RoomDrops.plaintext(event, bucket) } catch (_: RoomDrops.RumorTooLarge) { throw IllegalArgumentException(TOO_LONG) }
        val request = synchronized(queue) {
            if (stopped || publicationBlocked || generation != this.generation.get() || !stillAllowed())
                throw PublicationNotOfferedException()
            if (event.id in confirmedGuarded) return true
            check(queue.none { it.id == event.id }) { "This event already has a quiet publication owner" }
            check(queue.size < MAX_PENDING) { "quiet queue is full; the relay has not taken a slot in a long time" }
            GuardedPublication(event, generation, innerGeneration, stillAllowed).also {
                guarded[event.id] = it
                queue.addLast(event)
            }
        }
        try {
            return withTimeoutOrNull(timeoutMs) { request.receipt.await() } ?: run {
                if (request.attempted) throw PublicationUnconfirmedException()
                throw PublicationNotOfferedException("The quiet slot did not dispatch before the wait ended")
            }
        } finally {
            synchronized(queue) {
                if (guarded[event.id] === request) {
                    guarded.remove(event.id)
                    queue.removeAll { it.id == event.id }
                }
            }
        }
    }

    private fun allowed(request: GuardedPublication): Boolean =
        !stopped && !publicationBlocked && generation.get() == request.generation &&
            synchronized(queue) { guarded[request.event.id] === request } && request.stillAllowed()

    /** Caller holds queue's monitor. A possible offer remains ambiguous. */
    private fun rejectGuarded(request: GuardedPublication) {
        guarded.remove(request.event.id)
        queue.removeAll { it.id == request.event.id }
        request.receipt.completeExceptionally(if (request.attempted) PublicationUnconfirmedException()
            else PublicationNotOfferedException())
    }

    private fun state(): QuietState = synchronized(queue) {
        QuietState(keys.exportUsed(), queue.filterNot { it.id in guarded }, boxPending.toSet(), keyFingerprint, offeredThroughSlot)
    }

    fun exportState(): QuietState = state()

    private fun slotIndex(t: Long): Long = Math.floorDiv(t, intervalSeconds)

    private fun offsetFor(slot: Long): Long {
        if (slot != offsetSlot) {
            offsetSlot = slot
            offset = if (slotOffset != null) slotOffset.invoke(slot).coerceIn(0, intervalSeconds - 1) else {
                val b = random(4)
                val r = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
                (r * intervalSeconds) / 0x100000000L
            }
        }
        return offset
    }

    /**
     * Post whatever the current slot owes, once its moment has come: the
     * oldest queued event or a filler. The wrap is built once per slot and
     * kept until a relay takes it, so a retry re-posts the same event and
     * burns no counter. Two ticks never overlap.
     */
    suspend fun tick() {
        if (stopped || publicationBlocked) return
        if (!lock.tryLock()) return
        try {
            if (stopped || publicationBlocked) return
            val t = now()
            val slot = slotIndex(t)
            if (slot <= lastSlot) return
            if (t < slot * intervalSeconds + offsetFor(slot)) return
            val epoch = DeadDrop.epochIndexAt(t)
            if (range != null && range.all { it in reservedCounters(epoch) }) {
                lastSlot = slot
                current = null
                return
            }
            var built = current?.takeIf { it.slot == slot } ?: buildForSlot(slot).also { current = it }
            val stale = built.guard?.takeUnless(::allowed)
            if (stale != null) {
                synchronized(queue) { if (guarded[stale.event.id] === stale) rejectGuarded(stale) }
                current = null
                // An unconfirmed offer already occupied this slot. Never
                // replace it with another visible wrap in the same slot.
                if (stale.attempted) { lastSlot = slot; return }
                built = buildForSlot(slot).also { current = it }
            }
            // Persist spent counters before any offer, including temporary
            // guarded requests; a process restart must not reuse their key.
            if (!built.countersSaved) {
                offeredThroughSlot = maxOf(offeredThroughSlot, slot)
                onState(state())
                built.countersSaved = true
            }
            val taken = try {
                val guard = built.guard
                if (guard == null) inner.publishConfirmed(built.wrap)
                else {
                    guard.attempted = true
                    inner.publishConfirmedGuarded(built.wrap, guard.innerGeneration, { allowed(guard) })
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { false }
            if (!taken) return
            // Receipt and durable removal are separate gates. Keep the exact
            // wrap and queued event if saving the smaller queue fails, so a
            // retry cannot lose the message or consume another counter.
            val sentEvent = built.event
            synchronized(queue) {
                // A timed-out caller may already be retrying the same exact
                // event. Its late receipt belongs to that valid current owner
                // too; never remove the new request without completing it.
                val confirming = built.guard?.let { previous ->
                    guarded[previous.event.id]?.takeIf {
                        it.generation == previous.generation && allowed(it)
                    } ?: previous
                }
                val ownsHead = if (built.guard == null) sentEvent?.id !in guarded
                    else sentEvent != null && guarded[sentEvent.id] === confirming
                if (ownsHead && sentEvent != null && queue.firstOrNull()?.id == sentEvent.id) {
                    val retained = queue.drop(1).filterNot { it.id in guarded }
                    onState(QuietState(keys.exportUsed(), retained, boxPending.toSet(), keyFingerprint, offeredThroughSlot))
                    queue.removeFirst()
                }
                built.guard?.let { guard ->
                    if (guard.generation == generation.get()) {
                        confirmedGuarded.add(guard.event.id)
                        while (confirmedGuarded.size > MAX_PENDING) confirmedGuarded.remove(confirmedGuarded.first())
                    }
                    if (guarded[guard.event.id] === confirming) guarded.remove(guard.event.id)
                    confirming?.receipt?.complete(true)
                    guard.receipt.complete(true)
                }
            }
            lastSlot = slot
            current = null
        } finally {
            lock.unlock()
        }
    }

    private fun buildForSlot(slot: Long): SlotPublication {
        val t = now()
        keys.refresh(t)
        while (true) {
            val head = synchronized(queue) { queue.firstOrNull()?.takeUnless { it.id in boxPending } } ?: break
            val guard = synchronized(queue) { guarded[head.id] }
            if (guard != null && !allowed(guard)) {
                synchronized(queue) { rejectGuarded(guard) }
                continue
            }
            val key = try { keys.sendKey(member, t, range!!) } catch (_: QuietKeys.EpochExhausted) { break }
            try {
                return SlotPublication(slot, RoomDrops.createRoomDrop(head, key.publicKey, bucket, t, random), head, guard)
            } catch (_: RoomDrops.RumorTooLarge) {
                // The key is burnt and the message cannot be carried: drop it, try the next.
                synchronized(queue) { queue.removeFirst() }
            }
        }
        return SlotPublication(slot, RoomDrops.createRoomFiller(quietKinds.first(), bucket, t, random), null)
    }

    /** Match a wrap by its tag, open it, and hand the inner event to every quiet subscriber. */
    private fun receive(wrap: NostrEvent) {
        if (!RoomDrops.looksLikeWrap(wrap)) return
        synchronized(delivered) { if ("w:" + wrap.id in delivered) return }
        val t = now()
        keys.refresh(t)
        val tag = RoomDrops.tagOf(wrap) ?: return
        val current = keys.lookup(tag)
        val hit = current ?: lookupPast(tag, t) ?: return
        val inner = RoomDrops.openRoomDrop(wrap, hit.key.privateKey) ?: return
        synchronized(delivered) {
            keep("w:" + wrap.id)
            if ("i:" + inner.id in delivered) return
            keep("i:" + inner.id)
        }
        // A drop on this member's own key was posted by a device holding this
        // room key: its counter is spent here too.
        if (current != null && hit.member == member) keys.markUsed(member, hit.key.epochIndex, hit.key.counter, t)
        opened.tryEmit(inner)
    }

    private fun keep(id: String) {
        delivered.add(id)
        while (delivered.size > 8192) delivered.remove(delivered.first())
    }

    /**
     * One broadcast pull per transport however many subscriptions ride on
     * it: a live subscription reaching two days further back than the
     * lookback for the created_at jitter, and, on a real pool, a paged
     * backfill of the stored stream.
     */
    private fun ensureBroadcast() {
        if (broadcast != null) return
        val since = maxOf(0L, now() - HISTORY_SECONDS - RoomDrops.CREATED_AT_JITTER)
        broadcast = scope.launch {
            if (inner is RelayPool) launch { backfill(since) }
            inner.subscribe(listOf(Filter(kinds = listOf(RoomDrops.GIFT_WRAP_KIND), since = since))).collect { receive(it) }
        }
    }

    private suspend fun backfill(since: Long) {
        var until = now()
        var pages = 0
        val boundary = HashSet<String>()
        while (pages < 1000) {
            pages += 1
            val page = try {
                inner.queryAvailable(listOf(Filter(kinds = listOf(RoomDrops.GIFT_WRAP_KIND), since = since, until = until, limit = 500)))
            } catch (_: Exception) { return }
            val fresh = page.filter { it.id !in boundary }
            if (fresh.isEmpty()) return
            for (w in fresh) receive(w)
            val oldest = fresh.minOf { it.createdAt }
            if (oldest <= since) return
            boundary.clear(); boundary.addAll(fresh.filter { it.createdAt == oldest }.map { it.id })
            until = oldest
        }
    }

    override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> =
        if (filters.all { it.kinds != null && it.kinds.none { kind -> kind in quietKinds } })
            inner.subscribeReplayed(filters, onReplayComplete)
        else subscribe(filters)

    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
        val quiet = ArrayList<Filter>()
        val plain = ArrayList<Filter>()
        for (f in filters) {
            val kinds = f.kinds
            if (kinds == null) { plain.add(f); continue }
            val q = kinds.filter { it in quietKinds }
            val p = kinds.filter { it !in quietKinds }
            if (q.isNotEmpty()) quiet.add(f.copy(kinds = q))
            if (p.isNotEmpty()) plain.add(f.copy(kinds = p))
        }
        val flows = ArrayList<Flow<NostrEvent>>()
        if (plain.isNotEmpty()) flows.add(inner.subscribe(plain))
        if (quiet.isNotEmpty()) {
            ensureBroadcast()
            flows.add(opened.asSharedFlow().filter { e -> quiet.any { matches(it, e) } })
        }
        return merge(*flows.toTypedArray())
    }

    private fun matches(filter: Filter, event: NostrEvent): Boolean {
        if (filter.kinds != null && event.kind !in filter.kinds) return false
        if (filter.ids != null && event.id !in filter.ids) return false
        if (filter.authors != null && event.pubkey !in filter.authors) return false
        if (filter.since != null && event.createdAt < filter.since) return false
        if (filter.until != null && event.createdAt > filter.until) return false
        for ((name, wanted) in filter.tags) {
            val tag = name.removePrefix("#")
            val present = event.tags.filter { it.size >= 2 && it[0] == tag }.map { it[1] }
            if (present.none { it in wanted }) return false
        }
        return true
    }
}
