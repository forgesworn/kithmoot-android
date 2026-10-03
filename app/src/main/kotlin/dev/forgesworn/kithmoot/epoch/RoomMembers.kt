package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Who each room knows, for the known-members gate (kithmoot#207): once a room has removed
 * somebody, its key goes only to participants it knows, because a removed person back under a
 * fresh key and a newcomer with the link look the same to a desk.
 *
 * Two lists per room. The authority's member list, from the newest rekey or grant that carried
 * one: everybody it knows to be in the room, offline ones too, less the removed. And whoever was
 * let in from this device. Advisory, like the epoch history: a desk that cannot read it knows
 * fewer people, and asks about them instead, which is the safe way to be wrong.
 */
class RoomMembers(private val storage: RoomStorage) {
    private class Entry(val members: Set<String>, val letIn: Set<String>)

    private var cache: MutableMap<String, Entry>? = null

    /** Whether [stableRoom] knows [participant], other than through its roster. */
    @Synchronized fun knows(stableRoom: String, participant: String): Boolean {
        val p = participant.lowercase()
        val entry = rooms()[stableRoom] ?: return false
        return p in entry.members || p in entry.letIn
    }

    /** Replace the authority's member list for [stableRoom] with [members], the newest one seen. */
    @Synchronized fun setMembers(stableRoom: String, members: List<String>) {
        val rooms = rooms()
        val next = members.map(String::lowercase).filter(HEX::matches).toSet()
        val old = rooms[stableRoom]
        if (old?.members == next) return
        rooms[stableRoom] = Entry(next, old?.letIn.orEmpty())
        save(rooms)
    }

    /** Let [participant] into [stableRoom] from this device: its desks hand them the key. */
    @Synchronized fun letIn(stableRoom: String, participant: String) {
        val p = participant.lowercase()
        if (!HEX.matches(p)) return
        val rooms = rooms()
        val old = rooms[stableRoom]
        if (old != null && p in old.letIn) return
        rooms[stableRoom] = Entry(old?.members.orEmpty(), old?.letIn.orEmpty() + p)
        save(rooms)
    }

    @Synchronized fun forget(stableRoom: String) {
        val rooms = rooms()
        if (rooms.remove(stableRoom) != null) save(rooms)
    }

    private fun rooms(): MutableMap<String, Entry> {
        cache?.let { return it }
        val parsed = try {
            val bytes = storage.read()
            if (bytes == null) mutableMapOf() else {
                val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                require(root.getValue("version").jsonPrimitive.int == 1)
                root.getValue("rooms").jsonArray.associate { item ->
                    val room = item.jsonObject
                    val id = room.getValue("stableRoom").jsonPrimitive.content.also { require(HEX.matches(it)) }
                    id to Entry(room.keys("members"), room.keys("letIn"))
                }.toMutableMap()
            }
        } catch (_: Exception) {
            mutableMapOf()
        }
        cache = parsed
        return parsed
    }

    private fun save(rooms: MutableMap<String, Entry>) {
        cache = rooms
        try {
            storage.write(buildJsonObject {
                put("version", 1)
                put("rooms", JsonArray(rooms.entries.sortedBy { it.key }.map { (id, entry) ->
                    buildJsonObject {
                        put("stableRoom", id)
                        put("members", JsonArray(entry.members.sorted().map(::JsonPrimitive)))
                        put("letIn", JsonArray(entry.letIn.sorted().map(::JsonPrimitive)))
                    }
                }))
            }.toString().toByteArray())
        } catch (_: Exception) {
            // Advisory: kept in memory for this process, asked about again after a restart.
        }
    }

    private fun kotlinx.serialization.json.JsonObject.keys(name: String): Set<String> =
        (get(name) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.lowercase()?.takeIf(HEX::matches) }.toSet()

    private companion object {
        val HEX = Regex("[0-9a-f]{64}")
    }
}
