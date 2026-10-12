package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.toRustWireJson
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*
import java.util.UUID

enum class RecordingNodeHandoffState { PREPARED, OFFERED, QUEUED, CANCEL_PENDING, WITHDRAWN, CONFIRMED }

data class RecordingNodeHandoffBinding(
    val draftId: String,
    val accountPubkey: String,
    val nodeId: String,
    val leaseBody: String,
    val requestId: String,
    val event: NostrEvent,
    val publishBefore: Long,
) {
    val lease: JsonObject get() = Json.parseToJsonElement(leaseBody).jsonObject
    val room: String get() = lease.getValue("room").jsonPrimitive.content
    val queueBody: String get() = BoxCadence.guardedQueueBody(lease, requestId, event, publishBefore).toString()
    val path: String get() = BoxCadence.guardedQueuePath(lease.getValue("lease_id").jsonPrimitive.content)
}

data class RecordingNodeHandoff(val binding: RecordingNodeHandoffBinding, val state: RecordingNodeHandoffState)

/** Original request ownership, not an automatic outbox. Production supplies
 * device-encrypted, backup-excluded storage. No queue ACK confirms delivery.
 * Retry must separately revalidate the original room, signer and node lease. */
class RecordingNodeHandoffJournal(
    private val storage: RoomStorage,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private var entries = emptyList<RecordingNodeHandoff>()
    private val active = mutableSetOf<String>()
    private var recovered = false
    private var faulted = false

    @Synchronized fun recover() {
        check(!recovered)
        val bytes = storage.read()
        val decoded = try {
            bytes?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonArray.map(::decode) } ?: emptyList()
        } finally { bytes?.fill(0) }
        require(decoded.size <= MAX_ENTRIES && decoded.map { it.binding.draftId }.distinct().size == decoded.size &&
            decoded.map { it.binding.event.id }.distinct().size == decoded.size)
        entries = decoded
        recovered = true
    }

    @Synchronized fun pending(accountPubkey: String): List<RecordingNodeHandoff> {
        ready()
        return entries.filter { it.binding.accountPubkey == accountPubkey &&
            it.state !in setOf(RecordingNodeHandoffState.WITHDRAWN, RecordingNodeHandoffState.CONFIRMED) }
    }

    /** Must finish before reserving or offering any Link request. */
    @Synchronized fun prepare(binding: RecordingNodeHandoffBinding): RecordingNodeHandoff {
        ready(); validate(binding)
        check(binding.publishBefore > now()) { "The original recording publication expired" }
        val existing = entries.firstOrNull { it.binding.draftId == binding.draftId || it.binding.event.id == binding.event.id }
        if (existing != null) {
            check(existing.binding == binding) { "The recording already owns another node request" }
            check(existing.state !in setOf(RecordingNodeHandoffState.CANCEL_PENDING,
                RecordingNodeHandoffState.WITHDRAWN, RecordingNodeHandoffState.CONFIRMED))
            return existing
        }
        check(entries.size < MAX_ENTRIES) { "The recording node handoff journal is full" }
        val entry = RecordingNodeHandoff(binding, RecordingNodeHandoffState.PREPARED)
        persist(entries + entry)
        return entry
    }

    /** Explicit caller retry may replay this same request after a lost reply;
     * recovery itself never starts a request or changes its node/deadline. */
    @Synchronized fun reserveOffer(binding: RecordingNodeHandoffBinding): RecordingNodeHandoff {
        val entry = owned(binding)
        check(entry.state in setOf(RecordingNodeHandoffState.PREPARED, RecordingNodeHandoffState.OFFERED))
        check(binding.publishBefore > now()) { "The original recording publication expired" }
        check(binding.draftId !in active) { "The original recording already has an active node offer" }
        return update(entry, RecordingNodeHandoffState.OFFERED).also { active.add(binding.draftId) }
    }

    /** Cheap dispatch fence: no journal reads, signing or encrypted storage. */
    @Synchronized fun stillAllowed(binding: RecordingNodeHandoffBinding): Boolean =
        recovered && !faulted && binding.draftId in active && binding.publishBefore > now() && entries.any {
            it.binding == binding && it.state == RecordingNodeHandoffState.OFFERED
        }

    /** End a process-local attempt without assuming whether the node received
     * it. A subsequent explicit retry keeps the same durable request identity. */
    @Synchronized fun endOffer(binding: RecordingNodeHandoffBinding) {
        if (entries.any { it.binding == binding }) active.remove(binding.draftId)
    }

    @Synchronized fun queued(binding: RecordingNodeHandoffBinding): RecordingNodeHandoff {
        val entry = owned(binding)
        // A late ACK cannot revive a cancelled request. Keep its cancellation.
        if (entry.state == RecordingNodeHandoffState.CANCEL_PENDING) return entry
        check(entry.state in setOf(RecordingNodeHandoffState.OFFERED, RecordingNodeHandoffState.QUEUED))
        return update(entry, RecordingNodeHandoffState.QUEUED)
    }

    @Synchronized fun revokeRoom(room: String) {
        ready()
        persist(entries.map { entry ->
            if (entry.binding.room != room) entry else when (entry.state) {
                RecordingNodeHandoffState.PREPARED -> entry.copy(state = RecordingNodeHandoffState.WITHDRAWN)
                RecordingNodeHandoffState.OFFERED, RecordingNodeHandoffState.QUEUED ->
                    entry.copy(state = RecordingNodeHandoffState.CANCEL_PENDING)
                else -> entry
            }
        })
    }

    /** Call only after the original node's authenticated withdrawal receipt. */
    @Synchronized fun withdrawn(binding: RecordingNodeHandoffBinding): RecordingNodeHandoff {
        val entry = owned(binding)
        check(entry.state in setOf(RecordingNodeHandoffState.CANCEL_PENDING, RecordingNodeHandoffState.WITHDRAWN))
        return update(entry, RecordingNodeHandoffState.WITHDRAWN)
    }

    /** The transport must recognise this exact inner event from an authenticated
     * relay wrap. A node queue receipt or sent-item list is not this proof. */
    @Synchronized fun confirmRelay(binding: RecordingNodeHandoffBinding,
        receivedEventConfirmsPublication: (String) -> Boolean): RecordingNodeHandoff {
        val entry = owned(binding)
        check(entry.state in setOf(RecordingNodeHandoffState.OFFERED, RecordingNodeHandoffState.QUEUED))
        check(binding.publishBefore > now() && receivedEventConfirmsPublication(binding.event.id))
        return update(entry, RecordingNodeHandoffState.CONFIRMED)
    }

    /** Retire only after the original draft owner finishes its confirmed or
     * withdrawn handoff. Pending/uncertain records cannot be silently dropped. */
    @Synchronized fun retire(binding: RecordingNodeHandoffBinding) {
        val entry = owned(binding)
        check(entry.state in setOf(RecordingNodeHandoffState.WITHDRAWN, RecordingNodeHandoffState.CONFIRMED))
        persist(entries.filterNot { it == entry })
    }

    private fun ready() { check(recovered && !faulted) { "Recording node handoff storage is unavailable" } }
    private fun owned(binding: RecordingNodeHandoffBinding): RecordingNodeHandoff {
        ready()
        return entries.single { it.binding == binding }
    }
    private fun update(entry: RecordingNodeHandoff, state: RecordingNodeHandoffState): RecordingNodeHandoff {
        val next = entry.copy(state = state)
        persist(entries.map { if (it == entry) next else it })
        return next
    }
    private fun persist(next: List<RecordingNodeHandoff>) {
        val bytes = buildJsonArray { next.forEach { entry -> add(buildJsonObject {
            val binding = entry.binding
            put("draft", binding.draftId); put("account", binding.accountPubkey); put("node", binding.nodeId)
            put("lease", binding.leaseBody); put("request", binding.requestId); put("event", binding.event.toRustWireJson())
            put("publishBefore", binding.publishBefore); put("state", entry.state.name)
        }) } }.toString().toByteArray()
        try {
            storage.write(bytes)
            entries = next
            active.retainAll(next.filter { it.state == RecordingNodeHandoffState.OFFERED }.map { it.binding.draftId }.toSet())
        }
        catch (failure: Exception) { faulted = true; active.clear(); throw failure }
        finally { bytes.fill(0) }
    }
    private fun decode(value: JsonElement): RecordingNodeHandoff {
        val obj = value.jsonObject
        require(obj.keys == setOf("draft", "account", "node", "lease", "request", "event", "publishBefore", "state"))
        fun text(key: String) = obj.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        val binding = RecordingNodeHandoffBinding(text("draft"), text("account"), text("node"), text("lease"),
            text("request"), NostrEvent.fromJson(obj.getValue("event")), obj.getValue("publishBefore").jsonPrimitive.long)
        validate(binding)
        return RecordingNodeHandoff(binding, RecordingNodeHandoffState.valueOf(text("state")))
    }
    private fun validate(binding: RecordingNodeHandoffBinding) {
        require(UUID.fromString(binding.draftId).toString() == binding.draftId)
        require(binding.accountPubkey.matches(Regex("[0-9a-f]{64}")))
        val lease = binding.lease
        require(lease.toString() == binding.leaseBody && lease.getValue("server").jsonPrimitive.content == BoxCadence.server(binding.nodeId))
        binding.queueBody // Validate the exact signed event, request identity and original lease deadline.
    }
    companion object { private const val MAX_ENTRIES = 32 }
}
