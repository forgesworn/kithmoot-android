package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.UUID

/** An invitation, never an admitted device or a copied message archive.
 *  [admission] is the room secret (hex) of a group this account has joined, so
 *  another device opens the room without the group's signed invitation, which
 *  public relays drop within a day or two. It sits beside `room` in the record,
 *  is encrypted to the account's own key like the rest of it, and never names a
 *  temporary delegated admission. A relay may keep an old copy of a replaceable
 *  record, so a tombstone removes it only from the record the account reads.
 *  Wire-compatible with `admission` in app/src/room-bookmarks.ts.
 *
 *  A conference room's [endsAt] travels too, so the list on another device
 *  shows it ended without opening it; so do [destruct] and [startsAt], so
 *  another device knows the room self-destructs, and scales its countdown the
 *  same way, without opening it. Wire-compatible with
 *  `endsAt`, `destruct` and `startsAt` in the web client's `room` record. */
data class AccountRoom(val roomId: String, val link: String, val name: String?, val openedAt: Long, val admission: String? = null,
    val endsAt: Long? = null, val destruct: Boolean = false, val startsAt: Long? = null) {
    val label: String get() = name ?: "Room ${roomId.take(8)}"
    override fun toString(): String = "AccountRoom($roomId)"
}

data class RoomBookmarkSnapshot(val rooms: List<AccountRoom> = emptyList(), val ready: Boolean = false,
    val syncing: Boolean = false, val pending: Int = 0, val error: String? = null)

/** Wire-compatible with app/src/room-bookmarks.ts in the web app. */
class RoomBookmarks(
    private val signer: ParticipantSigner,
    private val transport: RoomTransport,
    private val storage: ProjectStorage,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Waits out [AccountWriteHold] before each relay publish. */
    private val beforePublish: suspend () -> Unit = {},
    /**
     * A room the account's records now say was removed, by another of the
     * person's devices or this one: told for each tombstone once the first
     * lookup has settled (so an old tombstone a relay sends before the newer
     * record that replaced it is never mistaken for the latest word), then
     * for each new one as it arrives. Must not block. The app wipes a
     * self-destructing room it still holds; any other room it leaves alone.
     */
    private val onTombstone: (String) -> Unit = {},
) {
    private data class Record(val event: NostrEvent, val value: JsonObject) {
        val roomId get() = value.getValue("roomId").jsonPrimitive.content
        val d get() = event.tags.first { it.firstOrNull() == "d" }[1]
    }
    private val gate = Mutex()
    private val syncGate = Mutex()
    private val sendGate = Mutex()
    private var records = linkedMapOf<String, Record>()
    private var pending = linkedMapOf<String, Record>()
    /** Records this lookup found still on a relay, sent again quietly after
     *  [pending] in case another relay has dropped them. */
    private val resend = ArrayDeque<NostrEvent>()
    /** Records already put back by this instance, by `d`. */
    private val repaired = mutableSetOf<String>()
    private var collector: Job? = null
    private var acquired = false
    private var loaded = false
    private var ready = false
    private var syncing = false
    private var fatal: String? = null
    private var error: String? = null
    @Volatile private var closed = false
    private val mutable = MutableStateFlow(RoomBookmarkSnapshot())
    val state: StateFlow<RoomBookmarkSnapshot> = mutable.asStateFlow()
    val identity get() = signer.pubkey
    private fun live() = check(!closed) { "Room sync has closed" }
    private fun emit() {
        val current = records + pending
        mutable.value = RoomBookmarkSnapshot(current.values.mapNotNull { room(it.value) }
            .sortedWith(compareByDescending<AccountRoom> { it.openedAt }.thenBy { it.roomId }),
            ready && fatal == null && !closed, syncing && !closed, pending.size, fatal ?: error)
    }
    private fun isRecord(e: NostrEvent): Boolean = e.kind == KIND && e.pubkey == identity &&
        e.content.length <= 60_000 && e.createdAt >= 0 && e.createdAt <= now() / 1000 + 60 &&
        e.tags.count { it.firstOrNull() == "d" } == 1 &&
        e.tags.any { it.size == 2 && it[0] == "d" && ADDRESS.matches(it[1]) } &&
        e.tags.any { it.size >= 2 && it[0] == "l" && it[1] == APP } && Events.verify(e)

    private fun validate(e: NostrEvent, value: JsonObject): Record {
        require(HEX.matches(value.getValue("roomId").jsonPrimitive.content))
        val at = value.getValue("at").jsonPrimitive
        require(!at.isString && at.long in 0..9_007_199_254_740_991L && at.long / 1000 == e.createdAt)
        room(value)
        return Record(e, value)
    }

    private fun room(value: JsonObject): AccountRoom? {
        val r = value["room"] ?: return null
        val obj = r.jsonObject
        val id = obj.getValue("roomId").jsonPrimitive.content
        require(id == value.getValue("roomId").jsonPrimitive.content)
        val link = obj.getValue("link").jsonPrimitive.content
        validateLink(link, id)
        val opened = obj.getValue("openedAt").jsonPrimitive
        require(!opened.isString && opened.long >= 0)
        // Read as the web reads them: an end that is not a positive safe
        // integer, a flag that is not exactly true, or a start that is not a
        // number is dropped, never the record.
        val ends = (obj["endsAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..9_007_199_254_740_991L }
        val destruct = (obj["destruct"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        val startsAt = (obj["startsAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
        return AccountRoom(id, link, DisplayName.sanitise(obj["name"]?.jsonPrimitive?.content), opened.long, admission(value, id),
            ends, destruct, startsAt)
    }

    /** A secret is kept only when it derives the room's own id; otherwise the
     *  room is still listed and the secret is ignored. */
    private fun admission(value: JsonObject, roomId: String): String? {
        val secret = (value["admission"] as? JsonObject)?.get("secret")?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
        if (!HEX.matches(secret)) return null
        return try { secret.takeIf { deriveRoom(it.hexToBytes()).roomId == roomId } } catch (_: Exception) { null }
    }

    private suspend fun persist(next: Map<String, Record> = records, out: Map<String, Record> = pending) {
        live()
        val raw = buildJsonObject {
            put("v", 1); put("identity", identity)
            fun entries(values: Map<String, Record>) = JsonArray(values.values.map {
                buildJsonObject { put("event", it.event.toJson()); put("value", it.value) }
            })
            put("records", entries(next)); put("pending", entries(out))
        }.toString()
        try {
            check(next.size <= 1024 && out.size <= 1024 && raw.toByteArray().size <= MAX_CACHE_BYTES)
            storage.save(raw)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fatal = "Room sync could not save its cache. Your existing data has been kept."; emit(); throw e
        }
        live()
    }

    suspend fun open() {
        try {
            gate.withLock {
                live(); check(!acquired); storage.acquire(); acquired = true
                storage.load()?.let { raw ->
                    require(raw.toByteArray().size <= MAX_CACHE_BYTES)
                    val root = Json.parseToJsonElement(raw).jsonObject
                    require(root["v"] == JsonPrimitive(1) && root["identity"] == JsonPrimitive(identity))
                    fun read(key: String): LinkedHashMap<String, Record> {
                        val entries = root.getValue(key).jsonArray; require(entries.size <= 1024)
                        val result = linkedMapOf<String, Record>()
                        for (entry in entries) {
                            val obj = entry.jsonObject; val event = NostrEvent.fromJson(obj.getValue("event"))
                            require(isRecord(event))
                            val record = validate(event, obj.getValue("value").jsonObject)
                            require(result.put(record.roomId, record) == null)
                        }
                        return result
                    }
                    records = read("records"); pending = read("pending")
                }
                loaded = true; emit()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fatal = "Saved room bookmarks could not be opened. Your data has been kept."; emit(); throw e
        }
        refresh()
    }

    suspend fun refresh() = syncGate.withLock {
        gate.withLock { live(); check(loaded && fatal == null); syncing = true; ready = false; error = null; emit() }
        collector?.cancelAndJoin()
        val filters = listOf(Filter(kinds = listOf(KIND), authors = listOf(identity), tags = mapOf("#l" to listOf(APP))))
        try {
            collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { transport.subscribe(filters).collect { receive(it) } }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    gate.withLock { ready = false; error = "Room sync stopped. Check your signer and relays, then retry."; emit() }
                }
            }
            val found = transport.queryAvailable(filters)
            for (event in found) receive(event)
            val tombstoned = gate.withLock { live(); repair(found); ready = collector?.isActive == true; syncing = false; emit()
                if (ready) (records + pending).filterValues { it.value["room"] == null }.keys.toList() else emptyList() }
            tombstoned.forEach { runCatching { onTombstone(it) } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            gate.withLock { syncing = false; ready = false
                error = "Room lookup could not finish. Check your signer's decryption permissions and Relay settings, then retry."; emit() }
        }
    }

    /**
     * Puts back this account's records the relays have lost. Relays drop
     * events, and devices read different relays: a room left on one relay
     * never reaches a device that does not read it, and nothing else would
     * send it again, because the device holding it counts it published. A
     * record no relay returned at its latest goes back into [pending]; every
     * other one is sent again quietly by [retry], since the lookup merges
     * relays and cannot say which still hold it. Each goes back as its exact
     * signed event, so a tombstone and the room it removed keep their order.
     * A lookup that found nothing may only mean no relay answered, so it
     * repairs nothing. Same as `#repair` in app/src/room-bookmarks.ts.
     */
    private suspend fun repair(found: List<NostrEvent>) {
        val seen = HashMap<String, Long>()
        for (event in found) if (isRecord(event)) {
            val d = event.tags.first { it.firstOrNull() == "d" }[1]
            seen[d] = maxOf(seen[d] ?: -1, event.createdAt)
        }
        if (seen.isEmpty()) return
        val lost = linkedMapOf<String, Record>()
        for ((roomId, record) in records) {
            if (roomId in pending || !repaired.add(record.d)) continue
            if ((seen[record.d] ?: -1) >= record.event.createdAt) resend.addLast(record.event)
            else lost[roomId] = record
        }
        if (lost.isEmpty()) return
        val out = LinkedHashMap(pending).apply { putAll(lost) }
        persist(out = out); pending = out
    }

    private suspend fun receive(event: NostrEvent) {
        val tombstone = receiveLocked(event)
        if (tombstone != null) runCatching { onTombstone(tombstone) }
    }

    /** The room a newly taken tombstone removes, once the first lookup has settled; null otherwise. */
    private suspend fun receiveLocked(event: NostrEvent): String? = gate.withLock {
        live(); if (fatal != null || !isRecord(event) || records.values.any { it.event.id == event.id }) return@withLock null
        val text = try { signer.nip44Decrypt(identity, event.content) }
        catch (e: CancellationException) { throw e }
        catch (e: SignerException) { throw e }
        catch (_: Exception) { return@withLock null }
        live()
        val incoming = try { validate(event, Json.parseToJsonElement(text).jsonObject) }
        catch (_: Exception) { return@withLock null }
        val old = records[incoming.roomId]
        if (old != null && !newer(event, old.event)) return@withLock null
        val next = LinkedHashMap(records).apply { put(incoming.roomId, incoming) }
        val out = LinkedHashMap(pending)
        out[incoming.roomId]?.let { if (it.event.id == event.id || newer(event, it.event)) out.remove(incoming.roomId) }
        persist(next, out); records = next; pending = out; emit()
        incoming.roomId.takeIf { ready && incoming.value["room"] == null && (out[incoming.roomId] ?: next[incoming.roomId]) === incoming }
    }

    suspend fun save(room: AccountRoom) {
        validateLink(room.link, room.roomId)
        require(HEX.matches(room.roomId) && room.openedAt >= 0)
        change(room.roomId, room)
    }

    suspend fun remove(roomId: String) { require(HEX.matches(roomId)); change(roomId, null) }

    private suspend fun change(roomId: String, room: AccountRoom?) {
        gate.withLock {
            live(); check(loaded && fatal == null) { "Room bookmarks are unavailable" }
            val old = pending[roomId] ?: records[roomId]
            val previous = old?.let { room(it.value) }
            // A save from a device that holds no secret keeps the one the record
            // carries: the record is last-writer-wins and would otherwise drop it.
            // Likewise the end, the start and self-destruct, which no save takes back.
            val cleaned = room?.copy(name = DisplayName.sanitise(room.name), admission = room.admission ?: previous?.admission,
                endsAt = room.endsAt ?: previous?.endsAt, destruct = room.destruct || previous?.destruct == true,
                startsAt = previous?.startsAt ?: room.startsAt)
            if (old != null && previous?.link == cleaned?.link && previous?.name == cleaned?.name && previous?.admission == cleaned?.admission &&
                previous?.endsAt == cleaned?.endsAt && previous?.destruct == cleaned?.destruct && previous?.startsAt == cleaned?.startsAt) return@withLock
            val at = maxOf(now(), ((old?.event?.createdAt ?: -1) + 1) * 1000)
            check(at <= now() + 60_000) { "Too many room changes at once. Try again shortly." }
            val value = buildJsonObject {
                put("roomId", roomId); put("at", at)
                cleaned?.let { r -> put("room", buildJsonObject {
                    put("roomId", r.roomId); put("link", r.link); r.name?.let { put("name", it) }
                    put("openedAt", r.openedAt); put("readAt", 0)
                    r.endsAt?.let { put("endsAt", it) }
                    if (r.destruct) put("destruct", true)
                    r.startsAt?.let { put("startsAt", it) }
                }) }
                cleaned?.admission?.let { put("admission", buildJsonObject { put("secret", it) }) }
            }
            val content = signer.nip44Encrypt(identity, value.toString()); live()
            val tags = listOf(listOf("d", old?.d ?: "$APP.${UUID.randomUUID()}"), listOf("l", APP))
            val event = checkedSignedEvent(signer.sign(KIND, at / 1000, tags, content), identity, KIND, at / 1000, tags, content)
            live()
            val out = LinkedHashMap(pending).apply { put(roomId, validate(event, value)) }
            persist(out = out); pending = out; emit()
        }
        retry()
    }

    /** Retries exact ciphertext already saved before its first publication. */
    suspend fun retry() = sendGate.withLock {
        try {
            while (true) {
                if (gate.withLock { pending.isEmpty() }) break
                beforePublish()
                val record = gate.withLock { live(); check(loaded && fatal == null); pending.values.firstOrNull() } ?: break
                check(transport.publishConfirmed(record.event)) { "No relay acknowledgement" }
                gate.withLock {
                    live()
                    val next = LinkedHashMap(records)
                    if (next[record.roomId]?.let { newer(it.event, record.event) } != true) next[record.roomId] = record
                    val out = LinkedHashMap(pending).apply { if (get(record.roomId)?.event?.id == record.event.id) remove(record.roomId) }
                    persist(next, out); records = next; pending = out; error = null; emit()
                }
            }
            while (true) {
                beforePublish()
                val event = gate.withLock {
                    live()
                    // A record changed since the lookup has its newer copy sent already.
                    generateSequence { resend.removeFirstOrNull() }.firstOrNull { old ->
                        records.values.any { it.event.id == old.id } && pending.values.none { it.d == old.tags.first { t -> t.firstOrNull() == "d" }[1] }
                    }
                } ?: break
                // Best effort: the relay that dropped it, or the next lookup.
                try { transport.publishConfirmed(event) } catch (e: Exception) { if (e is CancellationException) throw e }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            gate.withLock { error = "Room changes are saved on this phone, but relay sync was not confirmed. Retry room sync."; emit() }
        }
    }

    suspend fun close() {
        closed = true; collector?.cancelAndJoin()
        gate.withLock { if (acquired) { storage.release(); acquired = false }; emit() }
    }

    companion object {
        const val APP = "kithmoot.rooms.v1"
        const val KIND = 30078
        const val MAX_CACHE_BYTES = 8 * 1024 * 1024
        private val HEX = Regex("[0-9a-f]{64}")
        private val ADDRESS = Regex("kithmoot\\.rooms\\.v1\\.[0-9a-f-]{36}")
        private fun newer(a: NostrEvent, b: NostrEvent) = a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id < b.id)

        /** Decode locally; a synced URL must never open a remote website or enrol a device. */
        fun validateLink(link: String, roomId: String) {
            require(link.length <= 16_384)
            val fragment = link.substringAfter('#', "")
            val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(fragment), Charsets.UTF_8)).jsonObject
            require("c" !in payload && "k" !in payload) { "Pairing links cannot be synced" }
            if (decodeInvitationUrl(link) == null) {
                val legacy = decodeJoinUrl(link)
                try { require(deriveRoom(legacy.secret).roomId == roomId) } finally { legacy.secret.fill(0) }
            }
        }
    }
}
