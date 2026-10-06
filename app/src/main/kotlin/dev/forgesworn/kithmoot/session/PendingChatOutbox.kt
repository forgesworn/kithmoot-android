package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * What a retained message can honestly promise, which decides what a person may
 * do with it (see `pending-sends.ts` in the web app, which these mirror):
 *
 * - [WAITING]: never offered to a relay. Nothing has left this phone.
 * - [SENDING]: offered to the relays, no answer yet. Held in memory only; a
 *   journal that finds a message mid-send reads it as [UNKNOWN].
 * - [REFUSED]: every relay answered no. Nothing was kept anywhere.
 * - [UNKNOWN]: no relay answered in time. It may have arrived, and once it might
 *   have it never goes back to a state that says otherwise.
 * - [MOVED]: the room's key, credential or access changed before it went. It was
 *   never sent and never will be as it stands.
 */
enum class PendingChatState { WAITING, SENDING, REFUSED, UNKNOWN, MOVED;
    /** Nothing has left this phone, so editing or deleting it can promise that. */
    val clean: Boolean get() = this == WAITING || this == REFUSED || this == MOVED
}

/**
 * Exact, signed messages retained before publication, oldest first. Storage must
 * be atomic and encrypted. Each message is kept as the event that was signed, so
 * a retry offers the very same id and a relay that already has it keeps one copy.
 */
class PendingChatOutbox(
    private val storage: RoomStorage,
    private val roomId: String,
    private val participant: String,
    private val device: String,
) {
    /** [text] is kept beside the ciphertext because a message whose room key has
     *  since changed can no longer be read back from the event, and a person must
     *  still be able to copy it out. The journal is encrypted at rest. */
    data class Pending(
        val epochId: String,
        val event: NostrEvent,
        val state: PendingChatState = PendingChatState.WAITING,
        /** Only a plain new message can be put back in the composer; a reaction cannot. */
        val editable: Boolean = true,
        val text: String = "",
        /** The message's own id, which is what the chat's log knows it by; the event id is the relay's. */
        val messageId: String = "",
    )
    private val gate = locks.computeIfAbsent("$roomId:$participant:$device") { Mutex() }

    /** The oldest retained message, the next to go. */
    suspend fun pending(): Pending? = gate.withLock { withContext(Dispatchers.IO) { read().firstOrNull() } }

    suspend fun items(): List<Pending> = gate.withLock { withContext(Dispatchers.IO) { read() } }

    suspend fun retain(epochId: String, event: NostrEvent, editable: Boolean = true, text: String = "", messageId: String = "") = gate.withLock { withContext(Dispatchers.IO) {
        val items = read()
        check(items.size < MAX_ITEMS) { "Too many messages are waiting on this phone. Let some send, or remove some, first." }
        check(items.none { it.event.id == event.id }) { "This message is already waiting." }
        verified(event)
        write(items + Pending(epochId, event, PendingChatState.WAITING, editable, text, messageId))
    } }

    /**
     * Records what became of a message. [UNKNOWN] is sticky: a later refusal does
     * not clear the chance that an earlier attempt arrived, and a changed room
     * key does not turn a possibly-sent message into one never sent. [force]
     * restores a state when an attempt turned out never to have been offered.
     * Returns whether anything changed.
     */
    suspend fun setState(eventId: String, state: PendingChatState, force: Boolean = false): Boolean = gate.withLock { withContext(Dispatchers.IO) {
        require(state != PendingChatState.SENDING) { "Sending is not kept in the journal" }
        val items = read()
        val current = items.firstOrNull { it.event.id == eventId } ?: return@withContext false
        if (current.state == state || (!force && current.state == PendingChatState.UNKNOWN)) return@withContext false
        write(items.map { if (it.event.id == eventId) it.copy(state = state) else it })
        true
    } }

    /** Drops a message once a relay has confirmed that exact event, or the room shows it. */
    suspend fun confirm(eventId: String) = gate.withLock { withContext(Dispatchers.IO) {
        // A different operation must never erase another pending signed event.
        val items = read()
        if (items.any { it.event.id == eventId }) write(items.filter { it.event.id != eventId })
    } }

    /** Marks a message as offered, unless it was taken away meanwhile, and returns it as
     *  it stood. Written down before the offer, so a restart mid-send reads [UNKNOWN]. */
    suspend fun begin(eventId: String): Pending? = gate.withLock { withContext(Dispatchers.IO) {
        val items = read()
        val found = items.firstOrNull { it.event.id == eventId } ?: return@withContext null
        if (found.state != PendingChatState.UNKNOWN) write(items.map { if (it.event.id == eventId) it.copy(state = PendingChatState.UNKNOWN) else it })
        found
    } }

    /** Removes and returns a message only when nothing has left this phone. */
    suspend fun take(eventId: String, editableOnly: Boolean = false): Pending? = gate.withLock { withContext(Dispatchers.IO) {
        val items = read()
        val found = items.firstOrNull { it.event.id == eventId }?.takeIf { it.state.clean && (it.editable || !editableOnly) }
            ?: return@withContext null
        write(items.filter { it.event.id != eventId })
        found
    } }

    /** Removes a message whatever its state: for one that may already have arrived. */
    suspend fun remove(eventId: String): Pending? = gate.withLock { withContext(Dispatchers.IO) {
        val items = read()
        val found = items.firstOrNull { it.event.id == eventId } ?: return@withContext null
        write(items.filter { it.event.id != eventId })
        found
    } }

    suspend fun clear() = gate.withLock { withContext(Dispatchers.IO) { storage.reset() } }

    private fun verified(event: NostrEvent) {
        require(event.pubkey == device && event.kind == KIND_CHAT && Events.verify(event))
        require(event.toJson().toString().toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES) { "Pending message is too large" }
    }

    private fun write(items: List<Pending>) {
        if (items.isEmpty()) { storage.reset(); return }
        val value = buildJsonObject {
            put("v", 2); put("room", roomId); put("participant", participant); put("device", device)
            put("items", buildJsonArray {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("epoch", item.epochId); put("event", item.event.toJson())
                        put("state", item.state.name.lowercase()); put("editable", item.editable); put("text", item.text); put("messageId", item.messageId)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        require(value.size <= MAX_FILE_BYTES) { "Pending messages are too large" }
        try { storage.write(value) } finally { value.fill(0) }
    }

    private fun read(): List<Pending> {
        val bytes = storage.read() ?: return emptyList()
        try {
            require(bytes.size <= MAX_FILE_BYTES)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val version = root.getValue("v").jsonPrimitive.int
            require(version == 1 || version == 2)
            require(root.getValue("room").jsonPrimitive.content == roomId)
            require(root.getValue("participant").jsonPrimitive.content == participant)
            require(root.getValue("device").jsonPrimitive.content == device)
            // The one-slot journal could not say whether its message had gone,
            // and published straight after keeping it: so it may have.
            val rows: List<JsonObject> = if (version == 1) listOf(root) else root.getValue("items").jsonArray.map { it.jsonObject }
            require(rows.size <= MAX_ITEMS)
            return rows.map { row ->
                val event = NostrEvent.fromJson(row.getValue("event"))
                verified(event)
                val state = if (version == 1) PendingChatState.UNKNOWN else when (row["state"]?.jsonPrimitive?.content) {
                    "waiting" -> PendingChatState.WAITING
                    "refused" -> PendingChatState.REFUSED
                    "moved" -> PendingChatState.MOVED
                    else -> PendingChatState.UNKNOWN
                }
                Pending(row.getValue("epoch").jsonPrimitive.content, event, state,
                    row["editable"]?.jsonPrimitive?.boolean ?: false, row["text"]?.jsonPrimitive?.content.orEmpty(), row["messageId"]?.jsonPrimitive?.content.orEmpty())
            }
        } finally { bytes.fill(0) }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        /** The most unsent messages a room keeps, as the web app does. */
        const val MAX_ITEMS = 50
        /** One event is bounded by the relays long before this; the file holds many. */
        const val MAX_EVENT_BYTES = 64 * 1024
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }
}
