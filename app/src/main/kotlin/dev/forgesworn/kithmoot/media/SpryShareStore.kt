package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Rooms a person chose, on this device, to share their paired call relay in
 * although they did not make them ("Share my call relay here"). Rooms they
 * made share without asking. Kept on this device only, encrypted: never
 * synced, published or put in a link. The web keeps the same list as
 * `kithmoot.spry-share.v1`.
 */
internal class SpryShareStore(private val storage: RoomStorage) {
    constructor(context: Context) : this(EncryptedRoomStorage(context, ALIAS, LIMIT * 140))

    companion object {
        const val ALIAS = "kithmoot.spry-share.v1"
        const val LIMIT = 256
        private val ENTRY = Regex("^[0-9a-f]{64}:[0-9a-f]{64}$")
        private val lock = Any()

        /** The kept entries, newest last, at most [LIMIT]; anything else is dropped. */
        internal fun decode(bytes: ByteArray?): List<String> = runCatching {
            if (bytes == null || bytes.size > LIMIT * 140) return emptyList()
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(ENTRY::matches) }
                .distinct().takeLast(LIMIT)
        }.getOrDefault(emptyList())

        internal fun encode(entries: List<String>): ByteArray =
            JsonArray(entries.map { JsonPrimitive(it) }).toString().toByteArray(Charsets.UTF_8)
    }

    /** False when nothing was chosen, or the choice cannot be read: not shared. */
    fun read(participant: String, room: String): Boolean = synchronized(lock) {
        "$participant:$room" in decode(runCatching { storage.read() }.getOrNull())
    }

    fun save(participant: String, room: String, on: Boolean) = synchronized(lock) {
        val entry = "$participant:$room"
        require(ENTRY.matches(entry)) { "Invalid room." }
        val kept = decode(storage.read()).filter { it != entry }.let { if (on) it + entry else it }.takeLast(LIMIT)
        if (kept.isEmpty()) storage.reset() else storage.write(encode(kept))
    }
}
