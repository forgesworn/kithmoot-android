package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

enum class ForwardingLane { NEARBY, INTERNET }
enum class ForwardingVerdict { CURRENT, WAITING, MOVED }
enum class ForwardingObservation { RETAINED, DUPLICATE, REFUSED }
enum class ForwardingLaneState { WAITING, UNKNOWN, OFFERED, ACCEPTED, SEEN }

/** Explicit immutable authority/route binding. A changed selection cannot open
 * an old ledger or acquire a fresh retry budget through the same vault. */
class RoomForwardingBinding(val room: String, val participant: String, val device: String,
    val meshScope: String, relays: List<String>, senders: Set<String>) {
    val relays = relays.map(::canonicalRelayUrl).sorted()
    val senders = senders.toSortedSet().toSet()
    init {
        require(listOf(room, participant, device, meshScope).all(::forwardingHex))
        require(this.relays.size in 1..8 && this.relays.distinct().size == this.relays.size)
        require(this.senders.size in 1..32 && this.senders.all(::forwardingHex))
    }
    internal val owner = "$room:$participant:$device"
    internal val pin = Digests.sha256(buildJsonObject {
        put("profile", "native-room-forwarder-v1"); put("room", room)
        put("participant", participant); put("device", device); put("mesh", meshScope)
        put("relays", JsonArray(this@RoomForwardingBinding.relays.map(::JsonPrimitive)))
        put("senders", JsonArray(this@RoomForwardingBinding.senders.map(::JsonPrimitive)))
    }.toString().toByteArray(Charsets.UTF_8)).toHex()
}

/** Separate from PendingChatOutbox: third-party ciphertext, no plaintext and no
 * participant receipt. Call on an IO dispatcher; storage must be encrypted and
 * atomic. Only one live ledger owns a room/device. Reopen is suspended until a
 * matching live session binds current authority, regardless of saved queues. */
class RoomForwardingLedger(private val storage: RoomStorage, val binding: RoomForwardingBinding,
    private val nowMs: () -> Long = System::currentTimeMillis,
    createIfMissing: Boolean = false,
) : AutoCloseable {
    data class Lane(val state: ForwardingLaneState = ForwardingLaneState.WAITING,
        val attempts: Int = 0, val nextAt: Long = 0)
    data class Entry(val event: NostrEvent, val added: Long, val expires: Long,
        val moved: Boolean = false, val nearby: Lane = Lane(), val internet: Lane = Lane()) {
        fun lane(lane: ForwardingLane) = if (lane == ForwardingLane.NEARBY) nearby else internet
        fun withLane(lane: ForwardingLane, value: Lane) =
            if (lane == ForwardingLane.NEARBY) copy(nearby = value) else copy(internet = value)
    }
    data class Reservation(val event: NostrEvent, val lane: ForwardingLane, val attempt: Int)
    data class Status(val suspended: Boolean, val failed: Boolean, val high: Long, val expired: Long,
        val entries: List<Entry>, val nearbyBytes: Int, val internetBytes: Int)
    private data class Spend(val at: Long, val lane: ForwardingLane, val bytes: Int)
    private val lock = Any()
    private val lease = Any()
    private var authority: ((NostrEvent, Long) -> ForwardingVerdict)? = null
    private var high = 0L
    private var expired = 0L
    private var entries = listOf<Entry>()
    private var spends = listOf<Spend>()
    @Volatile private var failed = false
    @Volatile private var closed = false

    init {
        check(owners.putIfAbsent(binding.owner, lease) == null) { "This room already has a sharing owner" }
        try {
            val bytes = storage.read()
            if (bytes == null) check(createIfMissing) { "The sharing ledger is missing" }
            else try { restore(bytes) } finally { bytes.fill(0) }
            roll(); save()
        } catch (error: Exception) { closed = true; owners.remove(binding.owner, lease); throw error }
    }

    fun bind(room: String, participant: String, device: String,
        check: (NostrEvent, Long) -> ForwardingVerdict) = synchronized(lock) {
        usable()
        require(room == binding.room && participant == binding.participant && device == binding.device)
        authority = check
    }

    fun suspendExports() = synchronized(lock) { authority = null }

    /** Called only for an observed incoming lane. Source and exact signed event
     * become durable in one commit, including after a late opposite-lane copy. */
    fun observe(value: NostrEvent, source: ForwardingLane): ForwardingObservation = synchronized(lock) {
        usable(); roll()
        val event = checked(value)
        val decision = verdict(event)
        if (decision != ForwardingVerdict.CURRENT) { save(); return@synchronized ForwardingObservation.REFUSED }
        val old = entries.firstOrNull { it.event.id == event.id }
        if (old?.moved == true) { save(); return@synchronized ForwardingObservation.REFUSED }
        if (old?.lane(source)?.state == ForwardingLaneState.SEEN) {
            save(); return@synchronized ForwardingObservation.DUPLICATE
        }
        val row = old ?: run {
            val end = minOf(high + TTL_MS, event.createdAt * 1000 + TTL_MS, expiration(event) ?: MAX_CLOCK)
            if (end <= high || event.createdAt * 1000 > high + CLOCK_SKEW_MS ||
                entries.size >= MAX_ENTRIES || entries.sumOf { eventBytes(it.event) } + eventBytes(event) > MAX_BYTES) {
                save(); return@synchronized ForwardingObservation.REFUSED
            }
            Entry(event, high, end)
        }
        val observed = row.withLane(source, row.lane(source).copy(state = ForwardingLaneState.SEEN))
        entries = if (old == null) entries + observed else entries.map { if (it.event.id == event.id) observed else it }
        save(); ForwardingObservation.RETAINED
    }

    /** Persist UNKNOWN and spend debt before returning the event for handoff.
     * A crash after this point cannot say that it never left, or restore credit. */
    fun reserve(eventId: String, destination: ForwardingLane): Reservation? = synchronized(lock) {
        usable(); roll()
        val row = entries.firstOrNull { it.event.id == eventId }
        if (row == null || row.moved) { save(); return@synchronized null }
        when (verdict(row.event)) {
            ForwardingVerdict.WAITING -> { save(); return@synchronized null }
            ForwardingVerdict.MOVED -> {
                entries = entries.map { if (it.event.id == eventId) it.copy(moved = true) else it }
                save(); return@synchronized null
            }
            ForwardingVerdict.CURRENT -> Unit
        }
        val lane = row.lane(destination)
        val bytes = eventBytes(row.event)
        if (lane.state in setOf(ForwardingLaneState.SEEN, ForwardingLaneState.ACCEPTED) ||
            lane.attempts >= MAX_ATTEMPTS || lane.nextAt > high || spends.size >= MAX_SPENDS ||
            spends.filter { it.lane == destination }.sumOf { it.bytes } + bytes > LANE_BYTES) {
            save(); return@synchronized null
        }
        val next = lane.copy(state = ForwardingLaneState.UNKNOWN, attempts = lane.attempts + 1,
            nextAt = high + minOf(300_000L, 5_000L * (1L shl minOf(6, lane.attempts))))
        entries = entries.map { if (it.event.id == eventId) row.withLane(destination, next) else it }
        spends = spends + Spend(high, destination, bytes)
        save(); Reservation(copyEvent(row.event), destination, next.attempts)
    }

    /** Cheap in-memory guard for a transport's dispatch barrier. A source copy
     * arriving after reservation, suspension, expiry or revocation stops it. */
    fun canHandoff(reservation: Reservation): Boolean = synchronized(lock) {
        if (closed || failed) return@synchronized false
        high = maxOf(high, clock())
        val at = high
        val row = entries.firstOrNull { it.event.id == reservation.event.id } ?: return@synchronized false
        val lane = row.lane(reservation.lane)
        row.event == reservation.event && !row.moved && row.expires > at && lane.attempts == reservation.attempt &&
            lane.state == ForwardingLaneState.UNKNOWN && verdict(row.event, at) == ForwardingVerdict.CURRENT
    }

    /** API handoff on Nearby is OFFERED, never durable acceptance. An unknown
     * timeout/refusal needs no transition: reservation already recorded it. */
    fun offered(reservation: Reservation) = complete(reservation, ForwardingLaneState.OFFERED)
    fun relayAccepted(reservation: Reservation) {
        require(reservation.lane == ForwardingLane.INTERNET)
        complete(reservation, ForwardingLaneState.ACCEPTED)
    }
    private fun complete(reservation: Reservation, result: ForwardingLaneState) = synchronized(lock) {
        usable(); roll()
        entries = entries.map { row ->
            val lane = row.lane(reservation.lane)
            if (row.event == reservation.event && lane.attempts == reservation.attempt &&
                lane.state == ForwardingLaneState.UNKNOWN) row.withLane(reservation.lane, lane.copy(state = result)) else row
        }
        save()
    }

    fun status(): Status = synchronized(lock) {
        usable(); roll(); save()
        Status(authority == null, failed, high, expired, entries.map { it.copy(event = copyEvent(it.event)) },
            spends.filter { it.lane == ForwardingLane.NEARBY }.sumOf { it.bytes },
            spends.filter { it.lane == ForwardingLane.INTERNET }.sumOf { it.bytes })
    }
    fun persistenceFailed(): Boolean = failed

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; authority = null; owners.remove(binding.owner, lease) }
    }
    private fun usable() { check(!closed && !failed) { "Sharing is closed or persistence failed" } }
    private fun clock(): Long = nowMs().also { require(it in 0..MAX_CLOCK) }
    private fun verdict(event: NostrEvent, at: Long = high): ForwardingVerdict =
        try { authority?.invoke(copyEvent(event), at / 1000) ?: ForwardingVerdict.WAITING }
        catch (_: Exception) { ForwardingVerdict.WAITING }
    private fun roll() {
        high = maxOf(high, clock())
        val kept = entries.filter { it.expires > high }
        expired += entries.size - kept.size; entries = kept
        spends = spends.filter { high - it.at <= WINDOW_MS }
    }
    private fun checked(event: NostrEvent): NostrEvent {
        require(eventBytes(event) <= MAX_EVENT_BYTES && event.createdAt in 0..MAX_CLOCK / 1000 &&
            event.kind == KIND_CHAT && forwardingHex(event.id) && Events.verify(event))
        val streams = event.tags.filter { it.firstOrNull() == "d" }
        require(streams.size == 1 && streams.single().size == 2 && forwardingHex(streams.single()[1]))
        expiration(event)
        return copyEvent(event)
    }
    private fun expiration(event: NostrEvent): Long? {
        val tags = event.tags.filter { it.firstOrNull() == "expiration" }
        require(tags.size <= 1)
        return tags.singleOrNull()?.let {
            require(it.size == 2 && Regex("[0-9]+").matches(it[1]))
            val seconds = requireNotNull(it[1].toLongOrNull())
            require(seconds in 0..MAX_CLOCK / 1000); seconds * 1000
        }
    }

    private fun save() {
        usable()
        val bytes = buildJsonObject {
            put("v", 1); put("pin", binding.pin); put("high", high); put("expired", expired)
            put("entries", buildJsonArray { entries.forEach { row -> add(buildJsonObject {
                put("event", row.event.toJson()); put("added", row.added); put("expires", row.expires); put("moved", row.moved)
                for (lane in ForwardingLane.entries) put(lane.name, buildJsonObject {
                    val value = row.lane(lane); put("state", value.state.name); put("attempts", value.attempts); put("nextAt", value.nextAt)
                })
            }) } })
            put("spends", buildJsonArray { spends.forEach { add(buildJsonObject {
                put("at", it.at); put("lane", it.lane.name); put("bytes", it.bytes)
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= MAX_FILE_BYTES); storage.write(bytes) }
        catch (error: Exception) { failed = true; authority = null; throw error }
        finally { bytes.fill(0) }
    }
    private fun restore(bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_BYTES)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root.getValue("v").jsonPrimitive.int == 1 && root.getValue("pin").jsonPrimitive.content == binding.pin)
        high = root.getValue("high").jsonPrimitive.long; expired = root.getValue("expired").jsonPrimitive.long
        require(high in 0..MAX_CLOCK && expired >= 0)
        val rows = root.getValue("entries").jsonArray; require(rows.size <= MAX_ENTRIES)
        entries = rows.map { value ->
            val r = value.jsonObject; val event = checked(NostrEvent.fromJson(r.getValue("event")))
            val added = r.getValue("added").jsonPrimitive.long; val end = r.getValue("expires").jsonPrimitive.long
            require(added in 0..high && end > added && end <= added + TTL_MS &&
                end <= event.createdAt * 1000 + TTL_MS && end <= (expiration(event) ?: MAX_CLOCK))
            fun lane(name: ForwardingLane): Lane {
                val obj = r.getValue(name.name).jsonObject
                val status = ForwardingLaneState.valueOf(obj.getValue("state").jsonPrimitive.content)
                val attempts = obj.getValue("attempts").jsonPrimitive.int; val next = obj.getValue("nextAt").jsonPrimitive.long
                require(attempts in 0..MAX_ATTEMPTS && next in 0..high + 300_000L)
                require(name != ForwardingLane.NEARBY || status != ForwardingLaneState.ACCEPTED)
                require(status !in setOf(ForwardingLaneState.UNKNOWN, ForwardingLaneState.OFFERED, ForwardingLaneState.ACCEPTED) || attempts > 0)
                return Lane(status, attempts, next)
            }
            Entry(event, added, end, r.getValue("moved").jsonPrimitive.boolean, lane(ForwardingLane.NEARBY), lane(ForwardingLane.INTERNET)).also {
                require(it.nearby.state == ForwardingLaneState.SEEN || it.internet.state == ForwardingLaneState.SEEN)
            }
        }
        require(entries.map { it.event.id }.distinct().size == entries.size && entries.sumOf { eventBytes(it.event) } <= MAX_BYTES)
        val rowsSpent = root.getValue("spends").jsonArray; require(rowsSpent.size <= MAX_SPENDS)
        spends = rowsSpent.map {
            val r = it.jsonObject
            Spend(r.getValue("at").jsonPrimitive.long, ForwardingLane.valueOf(r.getValue("lane").jsonPrimitive.content),
                r.getValue("bytes").jsonPrimitive.int).also { s -> require(s.at in 0..high && s.bytes in 1..MAX_EVENT_BYTES) }
        }
        require(ForwardingLane.entries.all { lane -> spends.filter { it.lane == lane && high - it.at <= WINDOW_MS }.sumOf { it.bytes } <= LANE_BYTES })
    }
    companion object {
        private val owners = ConcurrentHashMap<String, Any>()
        const val MAX_ENTRIES = 100
        const val MAX_BYTES = 1024 * 1024
        const val MAX_EVENT_BYTES = 16 * 1024
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val WINDOW_MS = 62 * 60 * 1000L
        const val LANE_BYTES = 64 * 1024
        const val MAX_ATTEMPTS = 64
        private const val MAX_SPENDS = 2048
        private const val CLOCK_SKEW_MS = 300_000L
        private const val MAX_CLOCK = Long.MAX_VALUE - TTL_MS - WINDOW_MS
        private fun copyEvent(event: NostrEvent) = event.copy(tags = event.tags.map { it.toList() })
        private fun eventBytes(event: NostrEvent) = event.toJson().toString().toByteArray(Charsets.UTF_8).size
    }
}

private fun forwardingHex(value: String): Boolean = Regex("[0-9a-f]{64}").matches(value)
