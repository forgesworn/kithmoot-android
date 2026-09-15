package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * An encrypted, device-local catalogue of outer events the signed-in person
 * has already read or written in a room.
 *
 * NIP-77 compares outer Nostr event IDs and timestamps. Chat deliberately
 * uses an inner ID for display/deduplication, so a chat log is not suitable
 * reconciliation input. This catalogue holds no ciphertext or plaintext body,
 * relay URL, signer secret, credential or other person's events.
 */
class Nip77EventIndex(private val storage: RoomStorage) {
    /**
     * Records a verified outer room-chat event once it was accepted as a
     * message from [account] in [roomId]. The caller establishes that person
     * relationship after local decryption and credential validation.
     */
    @Synchronized fun record(account: String, roomId: String, event: NostrEvent) = guarded {
        val canonicalAccount = canonicalHex(account, "account")
        val canonicalRoom = canonicalHex(roomId, "room")
        require(event.kind == CHAT_KIND) { "Only room chat belongs in the NIP-77 index" }
        require(event.createdAt >= 0) { "NIP-77 event time is invalid" }
        require(Events.verify(event)) { "NIP-77 index refuses an unverified event" }
        val id = canonicalHex(event.id, "event id")
        val address = canonicalHex(requireNotNull(event.tagValue("d")), "chat address")

        val entries = read()
        val record = IndexedEvent(canonicalAccount, canonicalRoom, event.createdAt, id, address)
        val next = (entries.filterNot { it.account == record.account && it.id == record.id } + record)
            .filterNot { it.account == record.account && it.createdAt < event.createdAt - RETENTION_SECONDS }
            .sortedWith(compareBy<IndexedEvent> { it.account }.thenBy { it.createdAt }.thenBy { it.id })
            .let(::bounded)
        write(next)
    }

    /**
     * Produces only timestamp/ID pairs for one room address and a narrow
     * history window. A caller still has to obtain an explicit Link route and
     * NIP-42 permission before passing these to RelayPool.reconcileNip77.
     */
    @Synchronized fun records(
        account: String,
        roomId: String,
        address: String,
        since: Long,
        until: Long,
    ): List<Nip77Record> = guarded {
        require(since >= 0 && until >= since && until - since <= WINDOW_SECONDS) {
            "NIP-77 index requests one bounded history window"
        }
        val canonicalAccount = canonicalHex(account, "account")
        val canonicalRoom = canonicalHex(roomId, "room")
        val canonicalAddress = canonicalHex(address, "chat address")
        read().asSequence()
            .filter { it.account == canonicalAccount && it.roomId == canonicalRoom && it.address == canonicalAddress }
            .filter { it.createdAt in since..until }
            .sortedWith(compareByDescending<IndexedEvent> { it.createdAt }.thenByDescending { it.id })
            .take(Nip77Negentropy.MAX_RECORDS)
            .map { Nip77Record(it.createdAt.toULong(), it.id.hexToBytes()) }
            .toList()
    }

    @Synchronized fun clear(account: String) = guarded {
        val canonicalAccount = canonicalHex(account, "account")
        val retained = read().filterNot { it.account == canonicalAccount }
        if (retained.isEmpty()) storage.reset() else write(retained)
    }

    private fun bounded(entries: List<IndexedEvent>): List<IndexedEvent> =
        entries.groupBy { it.account }.flatMap { (_, accountEntries) ->
            accountEntries.sortedWith(compareByDescending<IndexedEvent> { it.createdAt }.thenByDescending { it.id })
                .take(MAX_EVENTS_PER_ACCOUNT)
        }.sortedWith(compareBy<IndexedEvent> { it.account }.thenBy { it.createdAt }.thenBy { it.id })

    private fun read(): List<IndexedEvent> {
        val bytes = storage.read() ?: return emptyList()
        try {
            require(bytes.size <= MAX_SERIALIZED_BYTES)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root.getValue("version").jsonPrimitive.int == VERSION)
            val events = root.getValue("events").jsonArray.map { IndexedEvent.fromJson(it.jsonObject) }
            require(events.size <= MAX_EVENTS_PER_ACCOUNT * MAX_ACCOUNTS)
            require(events.map(IndexedEvent::account).distinct().size <= MAX_ACCOUNTS)
            require(events.distinctBy { it.account to it.id }.size == events.size)
            return events
        } finally {
            bytes.fill(0)
        }
    }

    private fun write(entries: List<IndexedEvent>) {
        require(entries.size <= MAX_EVENTS_PER_ACCOUNT * MAX_ACCOUNTS)
        require(entries.map(IndexedEvent::account).distinct().size <= MAX_ACCOUNTS)
        val bytes = buildJsonObject {
            put("version", VERSION)
            put("events", JsonArray(entries.map(IndexedEvent::toJson)))
        }.toString().encodeToByteArray()
        try {
            require(bytes.size <= MAX_SERIALIZED_BYTES)
            storage.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (error is RoomStorageException) throw error
        throw RoomStorageException(error)
    }

    private data class IndexedEvent(
        val account: String,
        val roomId: String,
        val createdAt: Long,
        val id: String,
        val address: String,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("account", account)
            put("room", roomId)
            put("createdAt", createdAt)
            put("id", id)
            put("address", address)
        }

        companion object {
            fun fromJson(value: JsonObject): IndexedEvent = IndexedEvent(
                account = canonicalHex(value.getValue("account").jsonPrimitive.content, "account"),
                roomId = canonicalHex(value.getValue("room").jsonPrimitive.content, "room"),
                createdAt = value.getValue("createdAt").jsonPrimitive.long.also { require(it >= 0) },
                id = canonicalHex(value.getValue("id").jsonPrimitive.content, "event id"),
                address = canonicalHex(value.getValue("address").jsonPrimitive.content, "chat address"),
            )
        }
    }

    companion object {
        private const val VERSION = 1
        private const val CHAT_KIND = 1460
        private const val MAX_EVENTS_PER_ACCOUNT = 4_096
        private const val MAX_ACCOUNTS = 8
        private const val MAX_SERIALIZED_BYTES = 2 * 1024 * 1024
        private const val RETENTION_SECONDS = 30L * 24 * 60 * 60
        private const val WINDOW_SECONDS = 30L * 24 * 60 * 60

        private fun canonicalHex(value: String, label: String): String =
            value.normaliseHex().also {
                require(it.length == 64) { "NIP-77 $label is not a 32-byte hex value" }
                it.hexToBytes()
            }
    }
}
