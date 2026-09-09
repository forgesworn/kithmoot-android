package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.DeadDrop
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.QuietKeys
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.RoomTransport
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
    /** Start the slot timer. Tests drive [tick] by hand. */
    ticking: Boolean = true,
    /** Where inside each slot this device posts, in seconds from the slot's
     *  start. Drawn fresh per slot by default so posting times carry no fixed
     *  phase a relay could link across circuits. Tests pass `{ 0 }`. */
    private val slotOffset: ((Long) -> Long)? = null,
) : RoomTransport {

    companion object {
        const val SLOT_SECONDS: Long = 300
        const val BUCKET_BYTES: Int = 4096
        const val HISTORY_SECONDS: Long = 2L * 24 * 3600
        const val DEVICE_SLOTS: Int = 2
        const val MAX_PENDING: Int = 256
        const val CANNOT_SEND: String = "This device cannot post in a quiet room. Two devices per person can: the one holding your identity and the one it paired. Others read."
        const val TOO_LONG: String = "This message is too long for a quiet room. Every message in a quiet room is padded to one size, and this one does not fit."
        const val MEANING: String = "Quiet room. Messages ride the gift-wrap stream as dead drops, one every five minutes at most, and a relay cannot tell whether anything was said, by whom, or when. Being here still shows while you are here, and calls are not quiet."

        fun counterRange(slot: Int): IntRange? {
            if (slot < 0 || slot >= DEVICE_SLOTS) return null
            val width = DeadDrop.MAX_PER_EPOCH_ROOM / DEVICE_SLOTS
            return (slot * width) until ((slot + 1) * width)
        }
    }

    /** What survives a restart. */
    class QuietState(val used: Map<String, QuietKeys.UsedCounters>, val queued: List<NostrEvent>)

    private val member = member.lowercase()
    private val range = counterRange(slot)
    val canSend: Boolean = range != null && members.any { it.equals(member, ignoreCase = true) }

    private val keys = QuietKeys(lookbackEpochs = ((lookbackSeconds + DeadDrop.EPOCH_SECONDS - 1) / DeadDrop.EPOCH_SECONDS).toInt())
    private val lock = Mutex()
    private val queue = ArrayDeque<NostrEvent>()
    private var current: Triple<Long, NostrEvent, NostrEvent?>? = null
    private var lastSlot = -1L
    private var offsetSlot = -1L
    private var offset = 0L
    private val delivered = LinkedHashSet<String>()
    private val opened = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 512)
    private var broadcast: Job? = null
    private var timer: Job? = null
    private val ikm = DeadDrop.roomIkm(roomKey)

    init {
        keys.set(ikm, members)
        keys.refresh(now())
        if (restore != null) {
            keys.importUsed(restore.used, now())
            for (e in restore.queued) if (e.kind in quietKinds && queue.size < MAX_PENDING) queue.addLast(e)
        }
        if (ticking) timer = scope.launch {
            val everyMs = maxOf(1000L, minOf(intervalSeconds * 1000, 30_000L))
            while (isActive) { delay(everyMs); runCatching { tick() } }
        }
    }

    val pending: Int get() = queue.size

    fun stop() {
        timer?.cancel(); timer = null
        broadcast?.cancel(); broadcast = null
    }

    override fun describe(): List<String> = inner.describe()
    override fun circleRelays(): Set<String> = inner.circleRelays()

    /**
     * A quiet kind is queued for the next slot; an event too large for the
     * bucket is refused here, to the caller, not discovered at its slot.
     * Anything else goes straight to the relays.
     */
    override fun publish(event: NostrEvent) {
        if (event.kind !in quietKinds) return inner.publish(event)
        check(canSend) { CANNOT_SEND }
        try { RoomDrops.plaintext(event, bucket) } catch (_: RoomDrops.RumorTooLarge) { throw IllegalArgumentException(TOO_LONG) }
        synchronized(queue) {
            check(queue.size < MAX_PENDING) { "quiet queue is full; the relay has not taken a slot in a long time" }
            if (queue.none { it.id == event.id }) queue.addLast(event)
        }
        onState(state())
    }

    private fun state(): QuietState = synchronized(queue) { QuietState(keys.exportUsed(), queue.toList()) }

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
        if (!lock.tryLock()) return
        try {
            val t = now()
            val slot = slotIndex(t)
            if (slot <= lastSlot) return
            if (t < slot * intervalSeconds + offsetFor(slot)) return
            val built = current?.takeIf { it.first == slot } ?: buildForSlot(slot).also { current = it }
            val taken = try {
                if (inner is RelayPool) inner.publishConfirmed(built.second) else { inner.publish(built.second); true }
            } catch (_: Exception) { false }
            if (!taken) return
            lastSlot = slot
            synchronized(queue) { if (built.third != null && queue.firstOrNull()?.id == built.third!!.id) queue.removeFirst() }
            current = null
            if (built.third != null) onState(state())
        } finally {
            lock.unlock()
        }
    }

    private fun buildForSlot(slot: Long): Triple<Long, NostrEvent, NostrEvent?> {
        val t = now()
        keys.refresh(t)
        while (true) {
            val head = synchronized(queue) { queue.firstOrNull() } ?: break
            val key = try { keys.sendKey(member, t, range!!) } catch (_: QuietKeys.EpochExhausted) { break }
            try {
                return Triple(slot, RoomDrops.createRoomDrop(head, key.publicKey, bucket, t, random), head)
            } catch (_: RoomDrops.RumorTooLarge) {
                // The key is burnt and the message cannot be carried: drop it, try the next.
                synchronized(queue) { queue.removeFirst() }
            }
        }
        return Triple(slot, RoomDrops.createRoomFiller(quietKinds.first(), bucket, t, random), null)
    }

    /** Match a wrap by its tag, open it, and hand the inner event to every quiet subscriber. */
    private fun receive(wrap: NostrEvent) {
        if (!RoomDrops.looksLikeWrap(wrap)) return
        synchronized(delivered) { if ("w:" + wrap.id in delivered) return }
        val t = now()
        keys.refresh(t)
        val tag = RoomDrops.tagOf(wrap) ?: return
        val hit = keys.lookup(tag) ?: return
        val inner = RoomDrops.openRoomDrop(wrap, hit.key.privateKey) ?: return
        synchronized(delivered) {
            keep("w:" + wrap.id)
            if ("i:" + inner.id in delivered) return
            keep("i:" + inner.id)
        }
        // A drop on this member's own key was posted by a device holding this
        // room key: its counter is spent here too.
        if (hit.member == member) keys.markUsed(member, hit.key.epochIndex, hit.key.counter, t)
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
                (inner as RelayPool).queryStored(listOf(Filter(kinds = listOf(RoomDrops.GIFT_WRAP_KIND), since = since, until = until, limit = 500)))
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
