package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.SealedFile
import dev.forgesworn.kithmoot.session.mediaStorageOrigin
import dev.forgesworn.kithmoot.session.parseAttachment
import dev.forgesworn.kithmoot.session.toJson
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.util.UUID

data class RecordingShareDraft(
    val id: String,
    val origin: RecordingOrigin,
    val sourceName: String,
    val discardAt: Long?,
    val sealed: SealedFile,
    val storageOrigin: String? = null,
    val uploaded: ChatAttachment? = null,
)

/** A process-local reservation. Restarts discard unfinished encryption rather
 * than giving a late worker permission to resurrect a forgotten draft. */
class RecordingDraftTicket internal constructor(
    val id: String, val origin: RecordingOrigin, val sourceName: String,
    val discardAt: Long?, val destination: File, internal val owner: Any,
)

/** One application owner. Only ciphertext is kept in [directory]; descriptors,
 * recovery keys and original call metadata live in a device-encrypted journal.
 * Add, choosing an origin and retaining an upload receipt perform no network
 * action. Upload and Send remain separate explicit caller actions. */
class RecordingShareDraftStore(
    private val directory: File,
    private val journal: RoomStorage,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val reservations = mutableMapOf<String, RecordingDraftTicket>()
    private val revokedRooms = mutableSetOf<String>()
    private var entries = emptyList<RecordingShareDraft>()
    private var recovered = false
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    @Synchronized fun recover() {
        check(!recovered && reservations.isEmpty())
        check(directory.isDirectory || directory.mkdirs()) { "Private recording draft storage is unavailable" }
        val bytes = journal.read()
        val decoded = if (bytes == null) emptyList() else try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.map(::decode)
        } finally { bytes.fill(0) }
        require(decoded.size <= MAX_DRAFTS && decoded.map { it.id }.distinct().size == decoded.size)
        val retained = decoded.filter { available(it) && it.sealed.file.isFile && it.sealed.file.length() in 73L..MAX_BYTES }
        if (retained != decoded) persist(retained) else entries = retained
        val owned = retained.map { it.sealed.file.name }.toSet()
        directory.listFiles()?.filter { it.name !in owned }?.forEach {
            check(it.isFile && it.delete()) { "An unfinished encrypted draft could not be removed" }
        }
        recovered = true
    }

    @Synchronized fun list(room: String? = null): List<RecordingShareDraft> {
        requireRecovered()
        expire()
        return entries.filter { available(it) && (room == null || it.origin.room == room) }
    }

    @Synchronized fun begin(origin: RecordingOrigin, sourceName: String, discardAt: Long?): RecordingDraftTicket {
        requireRecovered(); expire()
        require(sourceName.isNotBlank() && sourceName == File(sourceName).name && sourceName.length <= 255)
        check(origin.room !in revokedRooms && (discardAt == null || discardAt > now())) { "The original room's recording is no longer available" }
        check(entries.size + reservations.size < MAX_DRAFTS) { "Send or remove an existing recording draft first" }
        check(entries.none { it.origin == origin && it.sourceName == sourceName } &&
            reservations.values.none { it.origin == origin && it.sourceName == sourceName }) { "This recording is already added to its original chat" }
        val id = UUID.randomUUID().toString()
        return RecordingDraftTicket(id, origin, sourceName, discardAt, File(directory, "$id.pending"), this).also { reservations[id] = it }
    }

    /** The caller must also revalidate the selected original local export at
     * this commit boundary. Encryption happens outside both owners' locks. */
    @Synchronized fun complete(ticket: RecordingDraftTicket, sealed: SealedFile): RecordingShareDraft {
        requireRecovered()
        check(reservations[ticket.id] === ticket && ticket.origin.room !in revokedRooms &&
            (ticket.discardAt == null || ticket.discardAt > now())) { "The original recording draft was revoked or expired" }
        require(sealed.file == ticket.destination && sealed.file.isFile && sealed.file.length() in 73L..MAX_BYTES)
        require(sealed.hash.matches(HEX) && sealed.key.matches(HEX) && sealed.name.length in 1..180 &&
            sealed.type in setOf("audio/mp4", "video/mp4", "audio/wav"))
        val destination = File(directory, "${ticket.id}.enc")
        check(!destination.exists() && sealed.file.renameTo(destination)) { "The encrypted draft could not be retained" }
        val draft = RecordingShareDraft(ticket.id, ticket.origin, ticket.sourceName, ticket.discardAt, sealed.copy(file = destination))
        try { persist(entries + draft) }
        catch (failure: Exception) { destination.delete(); throw failure }
        finally { reservations.remove(ticket.id) }
        return draft
    }

    @Synchronized fun abandon(ticket: RecordingDraftTicket) {
        require(ticket.owner === this)
        if (reservations[ticket.id] === ticket) reservations.remove(ticket.id)
        // Forget may precede the worker creating its pending file. Clean a
        // late file even though its reservation was already revoked.
        check(!ticket.destination.exists() || ticket.destination.delete()) { "The unfinished encrypted draft could not be removed" }
    }

    @Synchronized fun selected(id: String, room: String): RecordingShareDraft {
        requireRecovered(); expire()
        return entries.firstOrNull { it.id == id && it.origin.room == room && available(it) }
            ?: error("The recording draft is no longer available in its original room")
    }

    /** Once Upload chooses an origin, retries stay bound to it. A different
     * server requires a new explicitly added draft and independent file key. */
    @Synchronized fun bindOrigin(id: String, room: String, chosenOrigin: String): RecordingShareDraft {
        val draft = selected(id, room)
        val origin = mediaStorageOrigin(chosenOrigin)
        check(draft.storageOrigin == null || draft.storageOrigin == origin) { "This encrypted draft is already bound to its chosen storage origin" }
        if (draft.storageOrigin == origin) return draft
        val next = draft.copy(storageOrigin = origin)
        persist(entries.map { if (it.id == id) next else it })
        return next
    }

    @Synchronized fun retainUpload(id: String, room: String, origin: String, attachment: ChatAttachment): RecordingShareDraft {
        val draft = selected(id, room)
        validateReceipt(draft, origin, attachment)
        val next = draft.copy(uploaded = attachment)
        persist(entries.map { if (it.id == id) next else it })
        return next
    }

    private fun validateReceipt(draft: RecordingShareDraft, origin: String, attachment: ChatAttachment) {
        check(draft.storageOrigin == origin && origin == mediaStorageOrigin(origin)) { "The recording upload belongs to a different storage origin" }
        val uri = URI(attachment.url)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            mediaStorageOrigin("https://${uri.rawAuthority}") == origin &&
            uri.path.substringAfterLast('/').matches(Regex("${draft.sealed.hash}(?:\\.[a-z0-9]{1,10})?")))
        require(attachment.sha256 == draft.sealed.hash && attachment.key == draft.sealed.key &&
            attachment.name == draft.sealed.name && attachment.type == draft.sealed.type &&
            attachment.size != null && attachment.size in 73L..MAX_BYTES &&
            (!draft.sealed.file.isFile || attachment.size == draft.sealed.file.length()))
    }

    @Synchronized fun clearUpload(id: String, room: String, expected: ChatAttachment) {
        val draft = selected(id, room)
        if (draft.uploaded == expected) persist(entries.map { if (it.id == id) it.copy(uploaded = null) else it })
    }

    /** Remove only after an explicit discard or after Send retains the exact
     * attachment in the original room. The caller separately journals remote
     * deletion when discarding an uploaded copy. */
    @Synchronized fun remove(id: String, room: String): RecordingShareDraft {
        val draft = selected(id, room)
        persist(entries.filterNot { it.id == id })
        check(!draft.sealed.file.exists() || draft.sealed.file.delete()) { "The encrypted draft copy could not be removed" }
        return draft
    }

    @Synchronized fun forgetRoom(room: String) {
        requireRecovered(); require(room.matches(HEX))
        revokedRooms += room
        val pending = reservations.values.filter { it.origin.room == room }
        pending.forEach { reservations.remove(it.id) }
        val removed = entries.filter { it.origin.room == room }
        // Commit key removal before unlinking ciphertext. Failed persistence
        // throws and keeps current-process access revoked; it is not a wipe claim.
        if (removed.isNotEmpty()) persist(entries.filterNot { it.origin.room == room })
        (removed.map { it.sealed.file } + pending.map { it.destination }).forEach {
            check(!it.exists() || it.delete()) { "The original room's encrypted draft could not be removed" }
        }
    }

    private fun available(draft: RecordingShareDraft) = draft.origin.room !in revokedRooms &&
        (draft.discardAt == null || draft.discardAt > now())
    private fun requireRecovered() = check(recovered) { "Recording drafts must be recovered before use" }
    private fun expire() {
        val removed = entries.filterNot(::available)
        if (removed.isNotEmpty()) {
            persist(entries.filter(::available))
            removed.forEach { check(!it.sealed.file.exists() || it.sealed.file.delete()) { "An expired encrypted draft could not be removed" } }
        }
    }
    private fun persist(next: List<RecordingShareDraft>) {
        val bytes = JsonArray(next.map(::encode)).toString().toByteArray()
        try { journal.write(bytes) } finally { bytes.fill(0) }
        entries = next
        mutableRevision.value++
    }
    private fun encode(draft: RecordingShareDraft) = buildJsonObject {
        put("id", draft.id); put("room", draft.origin.room); put("call", draft.origin.call); put("roomName", draft.origin.name)
        put("sourceName", draft.sourceName); draft.discardAt?.let { put("discardAt", it) }
        put("hash", draft.sealed.hash); put("key", draft.sealed.key); put("name", draft.sealed.name); put("type", draft.sealed.type)
        draft.storageOrigin?.let { put("storageOrigin", it) }; draft.uploaded?.let { put("uploaded", it.toJson()) }
    }
    private fun decode(value: JsonElement): RecordingShareDraft {
        val obj = value.jsonObject
        fun string(key: String) = obj.getValue(key).jsonPrimitive.content
        val id = string("id"); require(UUID.fromString(id).toString() == id)
        val source = string("sourceName"); require(source == File(source).name && source.length in 1..255)
        val hash = string("hash"); val key = string("key"); require(hash.matches(HEX) && key.matches(HEX))
        val type = string("type"); require(type in setOf("audio/mp4", "video/mp4", "audio/wav"))
        val name = string("name"); require(name.length in 1..180)
        val origin = RecordingOrigin(string("room"), string("call"), string("roomName"))
        val deadline = obj["discardAt"]?.jsonPrimitive?.long.also { require(it == null || it > 0) }
        val storage = obj["storageOrigin"]?.jsonPrimitive?.content?.also { require(mediaStorageOrigin(it) == it) }
        val upload = obj["uploaded"]?.let { checkNotNull(parseAttachment(it)) }
        require(upload == null || storage != null)
        return RecordingShareDraft(id, origin, source, deadline, SealedFile(File(directory, "$id.enc"), hash, key, name, type), storage, upload).also {
            if (upload != null) validateReceipt(it, checkNotNull(storage), upload)
        }
    }

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        private const val MAX_DRAFTS = 4
        private const val MAX_BYTES = 260L * 1024 * 1024
    }
}
