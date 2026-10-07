package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One greyed row in the rooms list for a room that self-destructed. [id] is
 *  random, only to dismiss the row: it is not the room's id. */
data class DestructTombstone(val id: String, val at: Long)

/**
 * The rows a destroyed room leaves in the rooms list, so nobody is left
 * wondering where it went. Owner decision D2: a row names no room, and it goes
 * when dismissed or after seven days. Kept under one key that names no room
 * either, so the wipe that removes everything naming the room cannot take it,
 * and nothing here ties a tombstone to the room it was. The web client's
 * `kithmoot.destructed.v1`, for a phone.
 */
class DestructTombstones(private val read: () -> String?, private val write: (String?) -> Unit) {

    /** The rows to show, newest first. Those seven days old are dropped on the way through. */
    @Synchronized fun list(now: Long): List<DestructTombstone> {
        val all = load()
        val live = all.filter { it.at + TOMBSTONE_SECONDS > now }
        if (live.size != all.size) save(live)
        return live.sortedWith(compareByDescending<DestructTombstone> { it.at }.thenBy { it.id })
    }

    /** Leave a row for a room that self-destructed at [at]. */
    @Synchronized fun add(at: Long, id: String = Entropy.bytes(16).toHex()) {
        save((list(at) + DestructTombstone(id, at)).sortedByDescending { it.at }.take(MAX_TOMBSTONES))
    }

    @Synchronized fun dismiss(id: String) = save(load().filter { it.id != id })

    private fun load(): List<DestructTombstone> = try {
        Json.parseToJsonElement(read() ?: "[]").jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(ID::matches) ?: return@mapNotNull null
            val at = (obj["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return@mapNotNull null
            DestructTombstone(id, at)
        }
    } catch (_: Exception) { emptyList() }

    private fun save(rows: List<DestructTombstone>) =
        write(if (rows.isEmpty()) null else JsonArray(rows.map { buildJsonObject { put("id", it.id); put("at", it.at) } }).toString())

    companion object {
        const val TOMBSTONE_SECONDS = 7L * 86_400
        private const val MAX_TOMBSTONES = 50
        private const val PREFS = "kithmoot.destructed.v1"
        private const val KEY = "rows"
        private val ID = Regex("[0-9a-f]{16,64}")

        fun of(context: Context): DestructTombstones {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DestructTombstones({ prefs.getString(KEY, null) }, { value ->
                prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
            })
        }
    }
}
