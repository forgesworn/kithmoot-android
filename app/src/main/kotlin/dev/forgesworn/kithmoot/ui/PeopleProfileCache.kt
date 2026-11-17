package dev.forgesworn.kithmoot.ui

import android.content.Context
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.decodePublicProfile
import kotlinx.serialization.json.*

internal const val PEOPLE_PROFILE_FRESH_SECONDS = 24 * 60 * 60L
internal const val PEOPLE_PROFILE_EVENT_BYTES = 80_000
private const val RETAIN_SECONDS = 30 * PEOPLE_PROFILE_FRESH_SECONDS
private const val MAX_CACHE_BYTES = 2 * 1024 * 1024
private const val MAX_ENTRIES = 2_000

internal data class CachedPeopleProfiles(val profiles: Map<String, PublicProfile>, val fresh: Set<String>)

/** Dedicated account cache, never the room or contact vault. All instances
 * share a transaction lock, including the call and chat view models. Only
 * signed events are retained; every returned profile is verified again. */
internal class PeopleProfileCache(private val account: String, private val storage: RoomStorage,
    private val now: () -> Long = ::epochSeconds) {
    private data class Entry(val event: NostrEvent, val checked: Long)

    fun read(keys: Collection<String>): CachedPeopleProfiles = synchronized(transactions) {
        val requested = keys.toSet()
        val time = now()
        val entries = readEntries(time).filter { it.event.pubkey in requested }
        val profiles = entries.mapNotNull { entry ->
            decodePublicProfile(entry.event, requested, time)?.let { entry.event.pubkey to it }
        }.toMap()
        CachedPeopleProfiles(profiles, entries.filter { it.event.pubkey in profiles && time - it.checked < PEOPLE_PROFILE_FRESH_SECONDS }
            .map { it.event.pubkey }.toSet())
    }

    fun put(events: Collection<NostrEvent>) = synchronized(transactions) {
        if (events.isEmpty()) return@synchronized
        val time = now()
        val entries = readEntries(time).associateBy { it.event.pubkey }.toMutableMap()
        for (event in events) {
            if (event.toCompactJson().toByteArray().size > PEOPLE_PROFILE_EVENT_BYTES || decodePublicProfile(event, setOf(event.pubkey), time) == null) continue
            val previous = entries[event.pubkey]
            val newest = previous == null || newerProfile(event.createdAt, event.id, previous.event.createdAt, previous.event.id)
            // An older relay answer does not make the newer cached version fresh.
            if (newest || previous?.event?.id == event.id) entries[event.pubkey] = Entry(event, time)
        }
        val bounded = mutableListOf<Entry>()
        var size = encode(emptyList()).size
        for (entry in entries.values.sortedByDescending { it.checked }.take(MAX_ENTRIES)) {
            val addition = encodedEntry(entry).toString().toByteArray(Charsets.UTF_8).size + if (bounded.isEmpty()) 0 else 1
            if (size + addition > MAX_CACHE_BYTES) continue
            bounded += entry
            size += addition
        }
        storage.write(encode(bounded))
    }

    fun clear() = synchronized(transactions) { storage.reset() }

    private fun readEntries(time: Long): List<Entry> {
        val bytes = storage.read() ?: return emptyList()
        require(bytes.size <= MAX_CACHE_BYTES)
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(root["account"]?.jsonPrimitive?.content == account && root["version"]?.jsonPrimitive?.int == 1)
        val rows = root.getValue("profiles").jsonArray
        require(rows.size <= MAX_ENTRIES)
        return rows.mapNotNull { row -> runCatching {
            val obj = row.jsonObject
            val checked = obj.getValue("checked").jsonPrimitive.long
            require(checked in (time - RETAIN_SECONDS)..time)
            Entry(NostrEvent.fromJson(obj.getValue("event")), checked)
        }.getOrNull() }
    }

    private fun encode(entries: List<Entry>): ByteArray = buildJsonObject {
        put("version", 1); put("account", account)
        put("profiles", buildJsonArray { entries.forEach { add(encodedEntry(it)) } })
    }.toString().toByteArray(Charsets.UTF_8)

    private fun encodedEntry(entry: Entry) = buildJsonObject {
        put("checked", entry.checked); put("event", entry.event.toJson())
    }

    companion object {
        private val transactions = Any()
        fun onDevice(context: Context, account: String): PeopleProfileCache {
            require(account.matches(Regex("[0-9a-f]{64}")))
            return PeopleProfileCache(account, EncryptedRoomStorage(context, "kithmoot.people-profiles.v1.$account",
                MAX_CACHE_BYTES, "KithMoot people profiles v1:$account".toByteArray()))
        }
    }
}

/** NIP-01 replacement ordering: newest timestamp, lowest id on a tie. */
internal fun newerProfile(time: Long, id: String, oldTime: Long, oldId: String): Boolean =
    time > oldTime || time == oldTime && id < oldId

internal fun mergedPeopleProfiles(old: Map<String, PublicProfile>, incoming: Map<String, PublicProfile>, limit: Int): Map<String, PublicProfile> {
    val result = old.toMutableMap()
    for ((key, profile) in incoming) {
        val previous = result[key]
        if (previous == null || newerProfile(profile.createdAt, profile.eventId, previous.createdAt, previous.eventId)) result[key] = profile
    }
    return result.entries.toList().takeLast(limit).associate { it.key to it.value }
}
