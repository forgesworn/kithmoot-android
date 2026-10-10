package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.session.mediaAuthorisation
import dev.forgesworn.kithmoot.session.mediaStorageOrigin
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local ownership of one explicit upload; never persisted as authority. */
class RecordingUploadTicket internal constructor(val id: String, internal val owner: Any)

/** Storage keys have no room capabilities. The caller must choose and authorise
 * the origin explicitly, and persist this journal before offering upload bytes.
 * Production supplies device-encrypted storage. No file recovery keys belong here. */
class RecordingUploadJournal(private val storage: RoomStorage,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private data class Entry(val id: String, val origin: String, val hash: String,
        val room: String?, val deleteAt: Long?, val pending: Boolean, val dueAt: Long?)
    private var identities = emptyMap<String, String>()
    private var entries = emptyList<Entry>()
    private val active = mutableMapOf<String, RecordingUploadTicket>()
    private val deleting = mutableSetOf<String>()
    private val revokedRooms = mutableSetOf<String>()
    private val retrying = AtomicBoolean()
    private var recovered = false

    @Synchronized fun recover() {
        check(!recovered)
        val bytes = storage.read()
        if (bytes != null) try {
            val doc = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(doc.getValue("v").jsonPrimitive.int == 1)
            identities = doc.getValue("identities").jsonObject.mapValues { (origin, value) ->
                require(mediaStorageOrigin(origin) == origin)
                value.jsonPrimitive.content.also { require(it.matches(HEX)); publicKey(it) }
            }
            require(identities.size <= MAX_ORIGINS)
            entries = doc.getValue("entries").jsonArray.map { value ->
                val obj = value.jsonObject
                fun string(key: String) = obj.getValue(key).jsonPrimitive.content
                val id = string("id"); require(UUID.fromString(id).toString() == id)
                val origin = string("origin"); require(origin in identities)
                val hash = string("hash"); require(hash.matches(HEX))
                val room = obj["room"]?.jsonPrimitive?.content?.also { require(it.matches(HEX)) }
                val deadline = obj["deleteAt"]?.jsonPrimitive?.long?.also { require(it > 0) }
                val due = obj["dueAt"]?.jsonPrimitive?.long?.also { require(it >= 0) }
                Entry(id, origin, hash, room, deadline, obj.getValue("pending").jsonPrimitive.boolean, due)
            }
            require(entries.size <= MAX_ENTRIES && entries.map { it.id }.distinct().size == entries.size &&
                entries.map { it.origin to it.hash }.distinct().size == entries.size)
            // A crashed PUT may already have reached the node. Never assume
            // absence just because its receipt was not committed locally.
            val interrupted = entries.map { if (it.pending) it.copy(pending = false, dueAt = now() + GRACE) else it }
            if (interrupted != entries) persist(identities, interrupted)
        } finally { bytes.fill(0) }
        recovered = true
    }

    /** Returns only the public key the selected private node must allow. */
    @Synchronized fun identity(chosenOrigin: String): String {
        check(recovered)
        val origin = mediaStorageOrigin(chosenOrigin)
        val existing = identities[origin]
        if (existing != null) return publicKey(existing)
        check(identities.size < MAX_ORIGINS) { "The recording storage identity limit has been reached" }
        val secret = Entropy.bytes(32)
        try {
            val public = Schnorr.publicKeyHex(secret)
            persist(identities + (origin to secret.toHex()), entries)
            return public
        } finally { secret.fill(0) }
    }

    @Synchronized fun begin(room: String, origin: String, hash: String, deleteAt: Long?): RecordingUploadTicket {
        check(recovered); require(room.matches(HEX) && hash.matches(HEX))
        require(mediaStorageOrigin(origin) == origin && origin in identities)
        check(room !in revokedRooms && (deleteAt == null || deleteAt > now())) { "The original recording room is no longer available" }
        val old = entries.firstOrNull { it.origin == origin && it.hash == hash }
        check(old == null || (old.room == room && old.deleteAt == deleteAt && old.id !in active && old.id !in deleting)) { "This recording upload was revoked or is already running" }
        check(old != null || entries.size < MAX_ENTRIES) { "The recording cleanup journal is full" }
        val entry = Entry(old?.id ?: UUID.randomUUID().toString(), origin, hash, room, deleteAt, true, null)
        persist(identities, entries.filterNot { it.id == entry.id } + entry)
        return RecordingUploadTicket(entry.id, this).also { active[it.id] = it }
    }

    @Synchronized fun uploadAuthorisation(ticket: RecordingUploadTicket): NostrEvent {
        val entry = owned(ticket)
        check(entry.pending && entry.room != null && entry.room !in revokedRooms &&
            (entry.deleteAt == null || entry.deleteAt > now())) { "The original recording upload was revoked" }
        return sign(entry, "upload")
    }

    /** Forget during a PUT cannot turn its late receipt into a retained copy. */
    @Synchronized fun finish(ticket: RecordingUploadTicket, retained: Boolean) {
        val entry = owned(ticket)
        val allowed = retained && entry.room != null && entry.room !in revokedRooms &&
            (entry.deleteAt == null || entry.deleteAt > now()) && entry.dueAt == null
        persist(identities, entries.map { if (it.id == entry.id)
            it.copy(pending = false, dueAt = if (allowed) null else now() + GRACE) else it })
        active.remove(ticket.id)
    }

    @Synchronized fun forgetRoom(room: String) {
        check(recovered); require(room.matches(HEX)); revokedRooms += room
        persist(identities, entries.map { if (it.room == room)
            it.copy(room = null, dueAt = now() + GRACE) else it })
    }

    @Synchronized fun discard(origin: String, hash: String) {
        check(recovered); require(mediaStorageOrigin(origin) == origin && hash.matches(HEX))
        persist(identities, entries.map { if (it.origin == origin && it.hash == hash)
            it.copy(room = null, dueAt = now() + GRACE) else it })
    }

    /** No network beneath the journal lock. Failed or unconfirmed deletions
     * stay durable; each retry gets a fresh five-minute, exact-hash token. */
    fun retry(delete: (String, String, NostrEvent) -> Boolean) {
        if (!retrying.compareAndSet(false, true)) return
        try {
            val work = synchronized(this) {
                check(recovered)
                val expired = entries.map { if (it.deleteAt != null && it.deleteAt <= now() && it.dueAt == null)
                    it.copy(room = null, dueAt = now() + GRACE) else it }
                if (expired != entries) persist(identities, expired)
                entries.filter { it.id !in active && !it.pending && it.dueAt != null && it.dueAt <= now() }.take(8)
                    .map { it to sign(it, "delete") }.also { work -> deleting.addAll(work.map { it.first.id }) }
            }
            for ((entry, auth) in work) {
                val removed = try { delete(entry.origin, entry.hash, auth) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (removed) synchronized(this) {
                    // Explicit retrying Upload can supersede this snapshot.
                    if (entries.firstOrNull { it.id == entry.id } == entry && entry.id !in active)
                        persist(identities, entries.filterNot { it.id == entry.id })
                }
            }
        } finally { synchronized(this) { deleting.clear() }; retrying.set(false) }
    }

    private fun owned(ticket: RecordingUploadTicket): Entry {
        check(ticket.owner === this && active[ticket.id] === ticket)
        return entries.first { it.id == ticket.id }
    }
    private fun sign(entry: Entry, action: String): NostrEvent {
        val secret = identities.getValue(entry.origin).hexToBytes()
        try {
            val at = now()
            val expires = if (action == "upload") minOf(at + 300, entry.deleteAt ?: Long.MAX_VALUE) else at + 300
            return mediaAuthorisation(action, entry.hash, entry.origin, secret, at, expires)
        }
        finally { secret.fill(0) }
    }
    private fun publicKey(secret: String): String {
        val bytes = secret.hexToBytes()
        try { return Schnorr.publicKeyHex(bytes) } finally { bytes.fill(0) }
    }
    private fun persist(keys: Map<String, String>, next: List<Entry>) {
        val doc = buildJsonObject {
            put("v", 1); put("identities", JsonObject(keys.mapValues { JsonPrimitive(it.value) }))
            put("entries", JsonArray(next.map { e -> buildJsonObject {
                put("id", e.id); put("origin", e.origin); put("hash", e.hash); put("pending", e.pending)
                e.room?.let { put("room", it) }; e.deleteAt?.let { put("deleteAt", it) }; e.dueAt?.let { put("dueAt", it) }
            } }))
        }.toString().toByteArray()
        try { storage.write(doc) } finally { doc.fill(0) }
        identities = keys; entries = next
    }
    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        private const val GRACE = 90L
        private const val MAX_ORIGINS = 8
        private const val MAX_ENTRIES = 1000
    }
}
