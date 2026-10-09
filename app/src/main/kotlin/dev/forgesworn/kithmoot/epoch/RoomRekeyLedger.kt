package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.relay.RoomRoute
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class RoomRekeyBinding(val room: String, val authority: String, val device: String,
    val meshScope: String?, relays: List<String>, val route: RoomRoute) {
    constructor(room: String, authority: String, device: String, meshScope: String, relays: List<String>) :
        this(room, authority, device, meshScope, relays, RoomRoute.MIXED)
    val relays = relays.map(::canonicalRelayUrl).sorted()
    // Route/device changes cannot acquire a fresh journal and retry budget.
    val owner = "$room:$authority"
    init {
        require(listOf(room, authority, device).all(::rekeyHex))
        require(if (route.nearby) meshScope != null && rekeyHex(meshScope) else meshScope == null)
        require(this.relays.size <= 8 && this.relays.distinct().size == this.relays.size)
        require(if (route.internet) this.relays.isNotEmpty() else this.relays.isEmpty())
    }
    val pin = Digests.sha256(buildJsonObject {
        put("profile", "native-keeper-rekey-v1"); put("room", room); put("authority", authority)
        put("device", device); put("mesh", meshScope?.let(::JsonPrimitive) ?: JsonNull)
        if (route != RoomRoute.MIXED) put("route", route.stored)
        put("relays", JsonArray(this@RoomRekeyBinding.relays.map(::JsonPrimitive)))
    }.toString().toByteArray(Charsets.UTF_8)).toHex()
    fun permits(lane: RekeyLane) = if (lane == RekeyLane.NEARBY) route.nearby else route.internet
}

internal enum class RekeyLane { NEARBY, INTERNET }
internal enum class RekeyLaneState { WAITING, UNKNOWN, OFFERED, ACCEPTED }

/** Original outgoing root notices only. No signing key, incoming subscription
 * or receiver epoch state. Calls that persist must run on an IO dispatcher.
 * A restored owner is suspended until explicitly rebound to selected paths. */
internal class RoomRekeyLedger(private val storage: RoomStorage, val binding: RoomRekeyBinding,
    private val nowMs: () -> Long = System::currentTimeMillis, createIfMissing: Boolean = false) : AutoCloseable {
    data class Lane(val state: RekeyLaneState = RekeyLaneState.WAITING, val attempts: Int = 0, val nextAt: Long = 0)
    data class Entry(val event: NostrEvent, val epoch: Int, val added: Long, val expires: Long,
        val nearby: Lane = Lane(), val internet: Lane = Lane()) {
        fun lane(lane: RekeyLane) = if (lane == RekeyLane.NEARBY) nearby else internet
        fun withLane(lane: RekeyLane, value: Lane) = if (lane == RekeyLane.NEARBY) copy(nearby = value) else copy(internet = value)
    }
    data class Reservation(val event: NostrEvent, val lane: RekeyLane, val attempt: Int)
    data class Status(val suspended: Boolean, val high: Long, val expired: Long,
        val entries: List<Entry>, val nearbyBytes: Int, val internetBytes: Int)
    private data class Spend(val at: Long, val lane: RekeyLane, val bytes: Int)
    private val lock = ReentrantLock()
    private val lease = Any()
    private var selected: (() -> Boolean)? = null
    private var bound = false
    private var high = 0L
    private var expired = 0L
    private var entries = listOf<Entry>()
    private var spends = listOf<Spend>()
    @Volatile private var failed = false
    @Volatile private var closed = false

    init {
        synchronized(ownerGate(binding.owner)) {
            check(owners.putIfAbsent(binding.owner, lease) == null) { "This keeper already has a rekey courier" }
        }
        try {
            val bytes = storage.read()
            if (bytes == null) check(createIfMissing) { "The keeper rekey journal is missing" }
            else try { restore(bytes) } finally { bytes.fill(0) }
            roll(); save()
        } catch (error: Exception) {
            closed = true
            synchronized(ownerGate(binding.owner)) { owners.remove(binding.owner, lease) }
            throw error
        }
    }

    /** The guard must be cheap and in memory, without session or transport locks. */
    fun bind(stillSelected: () -> Boolean) = lock.withLock {
        usable(); check(!bound) { "This keeper journal already has a dispatch owner" }
        bound = true; selected = stillSelected
    }
    fun suspendExports() = lock.withLock { selected = null }
    /** Failed controller startup cannot close somebody else's dispatch owner. */
    fun closeIfUnbound() = lock.withLock { if (!bound) close() }

    /** Local durable admission only. Duplicate calls cannot re-sign or extend
     * the original event, expiry or debt. Authority state is owned separately. */
    fun admit(event: NostrEvent) = lock.withLock {
        usable()
        val original = checked(event)
        roll()
        check(allowed()) { "The selected keeper is suspended" }
        val epoch = requireNotNull(peekRekeyEpoch(original, binding.room, binding.authority))
        val old = entries.firstOrNull { it.epoch == epoch }
        if (old != null) {
            require(old.event == original) { "Conflicting keeper rekey for a retained epoch" }
            save(); return@withLock
        }
        val end = minOf(high + TTL_MS, original.createdAt * 1000 + TTL_MS, expiration(original) ?: MAX_CLOCK)
        require(end > high && original.createdAt * 1000 <= high + CLOCK_SKEW_MS) { "Keeper rekey is expired or future dated" }
        check(entries.size < MAX_ENTRIES && entries.sumOf { eventBytes(it.event) } + eventBytes(original) <= MAX_BYTES) {
            "Keeper rekey journal is full"
        }
        entries = entries + Entry(original, epoch, high, end)
        save()
    }

    /** UNKNOWN and byte debt commit before any caller can dispatch. */
    fun reserve(id: String, destination: RekeyLane): Reservation? = lock.withLock {
        usable(); roll()
        val row = entries.firstOrNull { it.event.id == id }
        if (row == null || !allowed() || !binding.permits(destination)) { save(); return@withLock null }
        val lane = row.lane(destination)
        val bytes = eventBytes(row.event)
        if (lane.state == RekeyLaneState.ACCEPTED || lane.attempts >= MAX_ATTEMPTS || lane.nextAt > high ||
            spends.size >= MAX_SPENDS || spends.filter { it.lane == destination }.sumOf { it.bytes } + bytes > LANE_BYTES) {
            save(); return@withLock null
        }
        val next = lane.copy(state = RekeyLaneState.UNKNOWN, attempts = lane.attempts + 1,
            nextAt = high + minOf(300_000L, 5_000L * (1L shl minOf(6, lane.attempts))))
        entries = entries.map { if (it.event.id == id) row.withLane(destination, next) else it }
        spends = spends + Spend(high, destination, bytes)
        save()
        Reservation(copyEvent(row.event), destination, next.attempts)
    }

    /** No IO or blocking queue lock beneath a transport dispatch barrier. */
    fun canHandoff(reservation: Reservation): Boolean {
        if (!lock.tryLock()) return false
        try {
            if (closed || failed || !allowed() || !binding.permits(reservation.lane)) return false
            high = maxOf(high, clock())
            val row = entries.firstOrNull { it.event.id == reservation.event.id } ?: return false
            val lane = row.lane(reservation.lane)
            return row.event == reservation.event && row.expires > high &&
                lane.state == RekeyLaneState.UNKNOWN && lane.attempts == reservation.attempt
        } finally { lock.unlock() }
    }

    fun offered(reservation: Reservation) = complete(reservation, RekeyLaneState.OFFERED)
    fun relayAccepted(reservation: Reservation) {
        require(reservation.lane == RekeyLane.INTERNET)
        complete(reservation, RekeyLaneState.ACCEPTED)
    }
    private fun complete(reservation: Reservation, state: RekeyLaneState) = lock.withLock {
        usable(); require(binding.permits(reservation.lane)); roll()
        entries = entries.map { row ->
            val lane = row.lane(reservation.lane)
            if (row.event == reservation.event && lane.attempts == reservation.attempt && lane.state == RekeyLaneState.UNKNOWN)
                row.withLane(reservation.lane, lane.copy(state = state)) else row
        }
        save()
    }
    fun status(): Status = lock.withLock {
        usable(); roll(); save()
        Status(!allowed(), high, expired, entries.map { it.copy(event = copyEvent(it.event)) },
            spends.filter { it.lane == RekeyLane.NEARBY }.sumOf { it.bytes },
            spends.filter { it.lane == RekeyLane.INTERNET }.sumOf { it.bytes })
    }
    fun persistenceFailed(): Boolean = failed
    override fun close() = lock.withLock {
        if (!closed) {
            closed = true; selected = null
            synchronized(ownerGate(binding.owner)) { owners.remove(binding.owner, lease) }
        }
    }
    private fun usable() { check(!closed && !failed) { "Keeper courier is closed or persistence failed" } }
    private fun allowed() = try { selected?.invoke() == true } catch (_: Exception) { false }
    private fun clock() = nowMs().also { require(it in 0..MAX_CLOCK) }
    private fun roll() {
        high = maxOf(high, clock())
        val kept = entries.filter { it.expires > high }
        expired += entries.size - kept.size; entries = kept
        spends = spends.filter { high - it.at <= WINDOW_MS }
    }
    private fun checked(event: NostrEvent): NostrEvent {
        require(eventBytes(event) <= MAX_EVENT_BYTES && event.createdAt in 0..MAX_CLOCK / 1000)
        require(event.kind == KIND_ROOM_REKEY && peekRekeyEpoch(event, binding.room, binding.authority) != null)
        for (name in listOf("d", "epoch")) {
            val tags = event.tags.filter { it.firstOrNull() == name }
            require(tags.size == 1 && tags.single().size == 2)
        }
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
                put("event", row.event.toJson()); put("epoch", row.epoch); put("added", row.added); put("expires", row.expires)
                for (lane in RekeyLane.entries) put(lane.name, buildJsonObject {
                    val state = row.lane(lane)
                    put("state", state.state.name); put("attempts", state.attempts); put("nextAt", state.nextAt)
                })
            }) } })
            put("spends", buildJsonArray { spends.forEach { add(buildJsonObject {
                put("at", it.at); put("lane", it.lane.name); put("bytes", it.bytes)
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= MAX_FILE_BYTES); storage.write(bytes) }
        catch (error: Exception) { failed = true; selected = null; throw error }
        finally { bytes.fill(0) }
    }
    private fun restore(bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_BYTES)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root.keys == setOf("v", "pin", "high", "expired", "entries", "spends"))
        require(integer(root, "v") == 1 && text(root, "pin") == binding.pin)
        high = long(root, "high"); expired = long(root, "expired")
        require(high in 0..MAX_CLOCK && expired >= 0)
        val rows = root.getValue("entries").jsonArray; require(rows.size <= MAX_ENTRIES)
        entries = rows.map { value ->
            val row = value.jsonObject
            require(row.keys == setOf("event", "epoch", "added", "expires", "NEARBY", "INTERNET"))
            val rawEvent = row.getValue("event").jsonObject
            require(rawEvent.keys == setOf("kind", "created_at", "tags", "content", "pubkey", "id", "sig"))
            integer(rawEvent, "kind"); long(rawEvent, "created_at")
            for (name in listOf("content", "pubkey", "id", "sig")) text(rawEvent, name)
            require(rawEvent.getValue("tags").jsonArray.all { tag -> tag.jsonArray.all { it.jsonPrimitive.isString } })
            val event = checked(NostrEvent.fromJson(rawEvent))
            val epoch = integer(row, "epoch")
            require(epoch == peekRekeyEpoch(event, binding.room, binding.authority))
            val added = long(row, "added"); val end = long(row, "expires")
            require(added in 0..high && end > added && end <= added + TTL_MS &&
                end <= event.createdAt * 1000 + TTL_MS && end <= (expiration(event) ?: MAX_CLOCK) &&
                event.createdAt * 1000 <= added + CLOCK_SKEW_MS)
            fun lane(name: RekeyLane): Lane {
                val obj = row.getValue(name.name).jsonObject
                require(obj.keys == setOf("state", "attempts", "nextAt"))
                val state = RekeyLaneState.valueOf(text(obj, "state"))
                val attempts = integer(obj, "attempts"); val nextAt = long(obj, "nextAt")
                require(attempts in 0..MAX_ATTEMPTS && nextAt in 0..high + 300_000L)
                require(name != RekeyLane.NEARBY || state != RekeyLaneState.ACCEPTED)
                require(binding.permits(name) || state == RekeyLaneState.WAITING && attempts == 0 && nextAt == 0L)
                require(if (state == RekeyLaneState.WAITING) attempts == 0 && nextAt == 0L else attempts > 0 && nextAt > 0)
                return Lane(state, attempts, nextAt)
            }
            Entry(event, epoch, added, end, lane(RekeyLane.NEARBY), lane(RekeyLane.INTERNET))
        }
        require(entries.map { it.epoch }.distinct().size == entries.size &&
            entries.map { it.event.id }.distinct().size == entries.size && entries.sumOf { eventBytes(it.event) } <= MAX_BYTES)
        val spent = root.getValue("spends").jsonArray; require(spent.size <= MAX_SPENDS)
        spends = spent.map {
            val row = it.jsonObject
            require(row.keys == setOf("at", "lane", "bytes"))
            Spend(long(row, "at"), RekeyLane.valueOf(text(row, "lane")), integer(row, "bytes"))
                .also { s -> require(s.at in 0..high && s.bytes in 1..MAX_EVENT_BYTES && binding.permits(s.lane)) }
        }
        require(RekeyLane.entries.all { lane -> spends.filter { it.lane == lane && high - it.at <= WINDOW_MS }.sumOf { it.bytes } <= LANE_BYTES })
    }
    companion object {
        private val owners = ConcurrentHashMap<String, Any>()
        private val ownerGates = ConcurrentHashMap<String, Any>()
        private fun ownerGate(owner: String) = ownerGates.computeIfAbsent(owner) { Any() }
        internal fun <T> withInactiveOwner(owner: String, action: () -> T): T = synchronized(ownerGate(owner)) {
            check(!owners.containsKey(owner)) { "Stop the keeper courier first" }; action()
        }
        const val MAX_ENTRIES = 16
        const val MAX_BYTES = 256 * 1024
        const val MAX_EVENT_BYTES = 16 * 1024
        const val MAX_FILE_BYTES = 512 * 1024
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val WINDOW_MS = 62 * 60 * 1000L
        const val LANE_BYTES = 16 * 1024
        const val MAX_ATTEMPTS = 8
        private const val MAX_SPENDS = MAX_ENTRIES * MAX_ATTEMPTS * 2
        private const val CLOCK_SKEW_MS = 300_000L
        private const val MAX_CLOCK = Long.MAX_VALUE - TTL_MS - WINDOW_MS
        private fun eventBytes(event: NostrEvent) = event.toJson().toString().toByteArray(Charsets.UTF_8).size
        private fun copyEvent(event: NostrEvent) = event.copy(tags = event.tags.map { it.toList() })
        private fun number(obj: JsonObject, name: String) = obj.getValue(name).jsonPrimitive.also { require(!it.isString) }
        private fun integer(obj: JsonObject, name: String) = number(obj, name).int
        private fun long(obj: JsonObject, name: String) = number(obj, name).long
        private fun text(obj: JsonObject, name: String) = obj.getValue(name).jsonPrimitive.also { require(it.isString) }.content
    }
}

private fun rekeyHex(value: String) = Regex("[0-9a-f]{64}").matches(value)
