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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** One exact, signed message retained before publication. Storage must be atomic and encrypted. */
class PendingChatOutbox(
    private val storage: RoomStorage,
    private val roomId: String,
    private val participant: String,
    private val device: String,
) {
    data class Pending(val epochId: String, val event: NostrEvent)
    private val gate = locks.computeIfAbsent("$roomId:$participant:$device") { Mutex() }

    suspend fun pending(): Pending? = gate.withLock { withContext(Dispatchers.IO) { read() } }

    suspend fun retain(epochId: String, event: NostrEvent) = gate.withLock { withContext(Dispatchers.IO) {
        check(read() == null) { "A message is waiting for relay confirmation. Retry it before sending another." }
        require(event.pubkey == device && event.kind == KIND_CHAT && Events.verify(event))
        val value = buildJsonObject {
            put("v", 1); put("room", roomId); put("participant", participant)
            put("device", device); put("epoch", epochId); put("event", event.toJson())
        }.toString().toByteArray(Charsets.UTF_8)
        require(value.size <= 64 * 1024) { "Pending message is too large" }
        try { storage.write(value) } finally { value.fill(0) }
    } }

    suspend fun confirm(eventId: String) = gate.withLock { withContext(Dispatchers.IO) {
        // A different operation must never erase the pending signed event.
        if (read()?.event?.id == eventId) storage.reset()
    } }

    suspend fun clear() = gate.withLock { withContext(Dispatchers.IO) { storage.reset() } }

    private fun read(): Pending? {
        val bytes = storage.read() ?: return null
        try {
            require(bytes.size <= 64 * 1024)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root.getValue("v").jsonPrimitive.int == 1)
            require(root.getValue("room").jsonPrimitive.content == roomId)
            require(root.getValue("participant").jsonPrimitive.content == participant)
            require(root.getValue("device").jsonPrimitive.content == device)
            val event = NostrEvent.fromJson(root.getValue("event"))
            require(event.pubkey == device && event.kind == KIND_CHAT && Events.verify(event))
            return Pending(root.getValue("epoch").jsonPrimitive.content, event)
        } finally { bytes.fill(0) }
    }

    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
