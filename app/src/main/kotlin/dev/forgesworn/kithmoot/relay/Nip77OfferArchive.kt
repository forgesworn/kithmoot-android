package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Encrypted, bounded outer-event retention used solely for an explicit
 * phone-to-Bothy custody offer after a NIP-77 comparison.
 *
 * This is separate from [Nip77EventIndex]: that index intentionally contains
 * metadata only. Nothing reads this archive automatically, and a caller must
 * still prove account, room, address, window and Link/NIP-42 authority before
 * it can publish an event.
 */
class Nip77OfferArchive(private val storage: RoomStorage) {
    @Synchronized fun record(account: String, roomId: String, event: NostrEvent) = guarded {
        val item = StoredEvent(
            account = canonicalHex(account, "account"),
            roomId = canonicalHex(roomId, "room"),
            event = checked(event),
        )
        val next = (read().filterNot { it.account == item.account && it.event.id == item.event.id } + item)
            .filterNot { it.account == item.account && it.event.createdAt < item.event.createdAt - RETENTION_SECONDS }
            .groupBy { it.account }
            .flatMap { (_, values) -> values.sortedWith(order).take(MAX_EVENTS_PER_ACCOUNT) }
            .sortedWith(compareBy<StoredEvent> { it.account }.then(order))
        write(next)
    }

    /** Only exact comparison IDs in one 30-day room window can be read back. */
    @Synchronized fun available(
        account: String,
        roomId: String,
        address: String,
        since: Long,
        until: Long,
        ids: Collection<String>,
    ): List<NostrEvent> = guarded {
        require(since >= 0 && until >= since && until - since <= RETENTION_SECONDS) {
            "NIP-77 offer requests one bounded history window"
        }
        val expected = ids.map { canonicalHex(it, "event id") }.toSet()
        require(expected.size in 1..Nip77Negentropy.MAX_RECORDS) { "NIP-77 offer IDs are not bounded" }
        val canonicalAccount = canonicalHex(account, "account")
        val canonicalRoom = canonicalHex(roomId, "room")
        val canonicalAddress = canonicalHex(address, "chat address")
        read().asSequence()
            .filter { it.account == canonicalAccount && it.roomId == canonicalRoom }
            .map { it.event }
            .filter { it.id in expected && it.createdAt in since..until && it.tagValue("d") == canonicalAddress }
            .filter(Events::verify)
            .sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            .toList()
    }

    @Synchronized fun clear(account: String) = guarded {
        val canonicalAccount = canonicalHex(account, "account")
        val retained = read().filterNot { it.account == canonicalAccount }
        if (retained.isEmpty()) storage.reset() else write(retained)
    }

    private fun read(): List<StoredEvent> {
        val bytes = storage.read() ?: return emptyList()
        try {
            require(bytes.size <= MAX_SERIALIZED_BYTES)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root.getValue("version").jsonPrimitive.int == VERSION)
            val events = root.getValue("events").jsonArray.map { StoredEvent.fromJson(it.jsonObject) }
            require(events.size <= MAX_EVENTS_PER_ACCOUNT * MAX_ACCOUNTS)
            require(events.map(StoredEvent::account).distinct().size <= MAX_ACCOUNTS)
            require(events.distinctBy { it.account to it.event.id }.size == events.size)
            return events
        } finally { bytes.fill(0) }
    }

    private fun write(entries: List<StoredEvent>) {
        val bytes = buildJsonObject {
            put("version", VERSION)
            put("events", JsonArray(entries.map(StoredEvent::toJson)))
        }.toString().encodeToByteArray()
        try {
            require(bytes.size <= MAX_SERIALIZED_BYTES)
            storage.write(bytes)
        } finally { bytes.fill(0) }
    }

    private fun checked(event: NostrEvent): NostrEvent {
        require(event.kind == CHAT_KIND && event.createdAt >= 0 && Events.verify(event)) {
            "NIP-77 offer archive refuses an invalid room chat"
        }
        canonicalHex(event.id, "event id")
        canonicalHex(requireNotNull(event.tagValue("d")), "chat address")
        return event
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (error is RoomStorageException) throw error
        throw RoomStorageException(error)
    }

    private data class StoredEvent(val account: String, val roomId: String, val event: NostrEvent) {
        fun toJson(): JsonObject = buildJsonObject {
            put("account", account)
            put("room", roomId)
            put("event", event.toJson())
        }

        companion object {
            fun fromJson(value: JsonObject): StoredEvent {
                val event = NostrEvent.fromJson(value.getValue("event").jsonObject)
                return StoredEvent(
                    canonicalHex(value.getValue("account").jsonPrimitive.content, "account"),
                    canonicalHex(value.getValue("room").jsonPrimitive.content, "room"),
                    checkedStatic(event),
                )
            }
        }
    }

    companion object {
        private const val VERSION = 1
        private const val CHAT_KIND = 1460
        private const val MAX_EVENTS_PER_ACCOUNT = 127
        private const val MAX_ACCOUNTS = 8
        private const val MAX_SERIALIZED_BYTES = 2 * 1024 * 1024
        private const val RETENTION_SECONDS = 30L * 24 * 60 * 60
        private val order = compareByDescending<StoredEvent> { it.event.createdAt }.thenByDescending { it.event.id }

        private fun canonicalHex(value: String, label: String): String =
            value.normaliseHex().also { require(it.length == 64) { "NIP-77 $label is not a 32-byte hex value" } }

        private fun checkedStatic(event: NostrEvent): NostrEvent {
            require(event.kind == CHAT_KIND && event.createdAt >= 0 && Events.verify(event)) { "NIP-77 offer archive refuses an invalid room chat" }
            canonicalHex(event.id, "event id")
            canonicalHex(requireNotNull(event.tagValue("d")), "chat address")
            return event
        }
    }
}
