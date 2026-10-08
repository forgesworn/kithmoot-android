package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.session.deleteUploadedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Device-encrypted, narrowly scoped delete permissions survive a room wipe until the host confirms deletion. */
class MediaUploadLedger(context: Context) {
    private val vault = EncryptedRoomStorage(context, "kithmoot.media-uploads.v1")
    private fun load(): List<JsonObject> = vault.read()?.let { bytes -> try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.map { it.jsonObject } } finally { bytes.fill(0) } }.orEmpty()
    private fun save(entries: List<JsonObject>) { val bytes = JsonArray(entries).toString().toByteArray(); try { vault.write(bytes) } finally { bytes.fill(0) } }
    fun record(room: String, origin: String, hash: String, deletion: NostrEvent) = synchronized(lock) {
        val entries = load(); require(entries.size < 1000) { "The file cleanup journal is full. Retry pending deletions first." }
        save(entries + buildJsonObject { put("room", room); put("origin", origin); put("hash", hash); put("deletion", deletion.toJson()) })
    }
    fun due(room: String? = null, hash: String? = null) = synchronized(lock) {
        val entries = load()
        val updated = entries.map { entry -> if ((room != null && entry["room"]?.jsonPrimitive?.content == room) || (hash != null && entry["hash"]?.jsonPrimitive?.content == hash))
            JsonObject(entry.filterKeys { it != "room" } + ("due" to JsonPrimitive(true)) + ("after" to JsonPrimitive(System.currentTimeMillis() / 1000 + 90))) else entry }
        if (updated != entries) save(updated)
    }
    fun retry() {
        if (!retrying.compareAndSet(false, true)) return
        try {
        val entries = synchronized(lock) { load().filter { it["due"]?.jsonPrimitive?.booleanOrNull == true && (it["after"]?.jsonPrimitive?.longOrNull ?: 0) <= System.currentTimeMillis() / 1000 && it.getValue("hash").jsonPrimitive.content !in inFlight } }
        for (entry in entries.take(8)) {
            val done = try { deleteUploadedMedia(entry.getValue("origin").jsonPrimitive.content, entry.getValue("hash").jsonPrimitive.content, NostrEvent.fromJson(entry.getValue("deletion").jsonObject)) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (done) synchronized(lock) { save(load().filterNot { it["hash"] == entry["hash"] && it["origin"] == entry["origin"] }) }
        }
        } finally { retrying.set(false) }
    }
    fun begin(hash: String) = synchronized(lock) { inFlight.add(hash); Unit }
    fun finish(hash: String) = synchronized(lock) { inFlight.remove(hash); Unit }
    companion object { private val retrying = java.util.concurrent.atomic.AtomicBoolean(); private val lock = Any(); private val inFlight = HashSet<String>() }
}
