package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.PublishEvidence
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Whether the relays kept a message this device sent: the one tick. A port of
 * the web client's `app/src/delivery-state.ts`, rule for rule.
 *
 * A relay's `OK` says it took the event, not that it will return it. A relay
 * that accepts chat and returns none of it looked exactly like a delivered
 * message. So a message is shown as sent only on evidence from a relay it went to:
 *
 * - a relay returned this very event when asked for it back after the write
 *   (whether or not its OK got through), or
 * - a relay that said OK to it has returned an event of its kind when asked,
 *   since its socket opened, and has not been seen to lose any.
 *
 * When every relay that was asked has not returned it, the message is "not
 * kept", which can be sent again. A message with no answer yet is "checking".
 * Nothing here says anyone else's device has it: that needs a delivery
 * receipt, which is a later change.
 *
 * Only this device's own messages are tracked. The verdict is kept per message
 * on the device, so a restart shows the same tick; a message this device sent
 * before the feature, or sent from another device, has none.
 *
 * A quiet room has no read-back: the drop being accepted is the whole of what
 * it can say, and the drop goes at the room's next slot.
 */
enum class TickState { CHECKING, SENT, NOT_KEPT }

/** What the relays' answers add up to for one message, or null when they cannot say (a relay
 *  that cannot be asked might hold it, or none is known).
 *
 *  [trustRelayLoss] false ignores a relay's earlier failure on another event, which a retry has
 *  not yet had the chance to answer for itself. [since] is when a retry began: what a relay did
 *  with the write before it is not an answer about this one. */
fun sentState(evidence: PublishEvidence?, trustRelayLoss: Boolean = true, since: Long? = null): TickState? {
    if (evidence == null) return null
    // After a retry, only relays that have answered the new write count.
    val relays = if (since == null) evidence.relays else evidence.relays.filter { !it.acked || it.at >= since }
    if (relays.isEmpty()) return if (since == null) null else TickState.CHECKING
    var unanswered = 0
    var unaskable = 0
    for (relay in relays) {
        // A relay that returned this very event holds it, whether or not its OK got through.
        if (relay.found == true) return TickState.SENT
        if (relay.found == false) continue
        // What a relay did with another event only speaks for this one if it said OK to this one.
        if (relay.acked && relay.keeps && !relay.lost) return TickState.SENT
        if (relay.acked && relay.lost && trustRelayLoss) continue
        if (relay.readable) unanswered++ else unaskable++
    }
    // A relay that cannot be asked might hold it: claim nothing either way.
    if (unaskable > 0) return null
    // A relay still working on the write, or not yet asked, may yet have it.
    if (unanswered > 0 || evidence.pending > 0) return TickState.CHECKING
    return TickState.NOT_KEPT
}

/** A message that renders as a row of its own: not a reaction, an edit, a retraction, an
 *  invitation, a room logo or a shared-work operation. */
fun isTickMessage(message: ChatMessage): Boolean =
    message.reaction == null && message.replaces == null && message.retracts == null &&
        message.invite == null && message.roomLogo == null && message.assignment == null

data class DeliveryRecord(
    /** The message's own id, as the chat shows it. */
    val id: String,
    /** The signed event id the relays acknowledged. */
    val eventId: String,
    val state: TickState,
    /** When a relay acknowledged it, in milliseconds. */
    val at: Long,
    /** The signed event, kept only while it is not kept, so Retry can offer the same bytes again. */
    val event: NostrEvent? = null,
    /** When the person sent it again, in milliseconds: what the relays said about an earlier write says nothing about this one. */
    val retriedAt: Long? = null,
    /** Sent in a quiet room, whose only evidence is its drop being accepted. Never stored: only settled verdicts are. */
    val quiet: Boolean = false,
)

/**
 * The ticks of one room's messages from this device. [storage] null keeps them
 * for this visit only, as a room that keeps nothing on the phone must.
 * Unreadable or unavailable storage is no evidence, so no tick.
 */
class DeliveryTicks(
    private val storage: RoomStorage?,
    /** What the pool knows about an event it wrote. */
    private val evidence: (String) -> PublishEvidence?,
    /** Asks the relays an event went to for it back, now. */
    private val askBack: (String) -> Unit = {},
    /** Whether a quiet room's drop carrying this inner event was accepted. */
    private val dropAccepted: (String) -> Boolean = { false },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val asked = mutableSetOf<String>()
    private val _records = MutableStateFlow(load())
    /** Every message with a tick, by message id. No entry means no tick. */
    val records: StateFlow<Map<String, DeliveryRecord>> = _records.asStateFlow()

    private fun load(): Map<String, DeliveryRecord> = try {
        val bytes = storage?.read()
        if (bytes == null) emptyMap() else try {
            if (bytes.size > MAX_FILE_BYTES) emptyMap() else {
                val parsed = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                (parsed as? JsonArray).orEmpty().mapNotNull(::readable).associateBy { it.id }
            }
        } finally { bytes.fill(0) }
    } catch (_: Exception) { emptyMap() }

    private fun readable(value: kotlinx.serialization.json.JsonElement): DeliveryRecord? {
        val row = value as? JsonObject ?: return null
        val id = (row["i"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val eventId = (row["e"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val state = when ((row["s"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "sent" -> TickState.SENT
            "not-kept" -> TickState.NOT_KEPT
            else -> return null
        }
        val at = (row["a"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
        if (at < 0 || at > Long.MAX_VALUE / 1000) return null
        val event = row["v"]?.let { stored -> runCatching { NostrEvent.fromJson(stored) }.getOrNull()?.takeIf { it.id == eventId } ?: return null }
        return DeliveryRecord(id, eventId, state, at * 1000, event)
    }

    /** Under [lock]: drop what is a week old, keep a bounded number of settled verdicts, and write them. */
    private fun save(records: Map<String, DeliveryRecord>): Map<String, DeliveryRecord> {
        val cutoff = now() - DELIVERY_KEPT_MS
        val current = records.filterValues { it.at >= cutoff }
        val settled = current.values.filter { it.state != TickState.CHECKING }.sortedBy { it.at }.takeLast(MAX_DELIVERY_KEPT)
        val withEvent = settled.filter { it.state == TickState.NOT_KEPT && it.event != null }.takeLast(MAX_KEPT_EVENTS).mapTo(HashSet()) { it.id }
        val target = storage ?: return current
        try {
            if (settled.isEmpty()) target.reset() else {
                val bytes = buildJsonArray {
                    settled.forEach { record ->
                        add(buildJsonObject {
                            put("i", record.id); put("e", record.eventId)
                            put("s", if (record.state == TickState.SENT) "sent" else "not-kept")
                            put("a", record.at / 1000)
                            if (record.id in withEvent) put("v", record.event!!.toJson())
                        })
                    }
                }.toString().toByteArray(Charsets.UTF_8)
                try { if (bytes.size <= MAX_FILE_BYTES) target.write(bytes) } finally { bytes.fill(0) }
            }
        } catch (_: Exception) { /* The tick still shows for this visit. */ }
        return current
    }

    private fun change(transform: (Map<String, DeliveryRecord>) -> Map<String, DeliveryRecord>?, persist: Boolean) = synchronized(lock) {
        val next = transform(_records.value) ?: return@synchronized
        _records.value = if (persist) save(next) else next
    }

    /** A relay acknowledged this message. In a [quiet] room it waits for its drop instead. */
    fun note(id: String, event: NostrEvent, quiet: Boolean = false) = change({
        it + (id to DeliveryRecord(id, event.id, TickState.CHECKING, now(), if (quiet) null else event, quiet = quiet))
    }, persist = false)

    fun get(id: String): DeliveryRecord? = _records.value[id]

    /** Whether any message still waits for an answer. */
    fun waiting(): Boolean = _records.value.values.any { it.state == TickState.CHECKING }

    /** The person pressed Retry: ask again, and let this write answer for itself. */
    fun retry(id: String): DeliveryRecord? = synchronized(lock) {
        val record = _records.value[id]
        if (record == null || record.state != TickState.NOT_KEPT || record.event == null) return null
        val at = now()
        val retried = record.copy(state = TickState.CHECKING, retriedAt = at, at = at)
        asked -= record.eventId
        _records.value = save(_records.value + (id to retried))
        retried
    }

    /** The republish itself failed: it is still not kept. */
    fun failed(id: String) = change({ records ->
        records[id]?.takeIf { it.state == TickState.CHECKING && it.event != null }?.let { records + (id to it.copy(state = TickState.NOT_KEPT)) }
    }, persist = true)

    /** Read what the relays now say about every message still waiting. */
    fun refresh() = synchronized(lock) {
        val at = now()
        var changed = false
        val next = _records.value.toMutableMap()
        val ask = mutableListOf<String>()
        for (record in _records.value.values) {
            if (record.state != TickState.CHECKING) continue
            if (record.quiet) {
                if (dropAccepted(record.eventId)) { next[record.id] = record.copy(state = TickState.SENT); changed = true }
                else if (at - record.at >= QUIET_GIVE_UP_AFTER_MS) { next.remove(record.id); changed = true }
                continue
            }
            val known = evidence(record.eventId)
            val state = sentState(known, trustRelayLoss = record.retriedAt == null, since = record.retriedAt)
            if (state == TickState.SENT || state == TickState.NOT_KEPT) {
                next[record.id] = record.copy(state = state, event = if (state == TickState.SENT) null else record.event)
                changed = true
                continue
            }
            if (at - record.at >= ASK_BACK_AFTER_MS && known != null && asked.add(record.eventId)) ask += record.eventId
            // Nothing can ever say: the relays that took it cannot be asked, or the
            // pool that took it is gone. No tick is better than a clock for ever.
            if ((state == null && at - record.at >= NO_EVIDENCE_AFTER_MS) || at - record.at >= GIVE_UP_AFTER_MS) {
                next.remove(record.id); changed = true
            }
        }
        if (changed) _records.value = save(next)
        ask
    }.forEach(askBack)

    /** The room is closing with clocks still showing: no answer will come for them in this visit. */
    fun giveUp() = change({ records ->
        records.filterValues { it.state != TickState.CHECKING }.takeIf { it.size != records.size }
    }, persist = true)

    /** Forget every verdict, as when the room is wiped from the phone. */
    fun forget() = synchronized(lock) {
        _records.value = emptyMap()
        try { storage?.reset() } catch (_: Exception) { /* Nothing more to forget. */ }
    }

    companion object {
        /** A message's verdict is kept this long: a week, as long as anything else about delivery is. */
        const val DELIVERY_KEPT_MS: Long = 7L * 24 * 3600 * 1000
        /** Most verdicts kept for a room. */
        const val MAX_DELIVERY_KEPT: Int = 300
        /** How long a message may wait for the one check per relay before it is asked about by name. */
        const val ASK_BACK_AFTER_MS: Long = 15_000
        /** How long a message with no evidence at all keeps its clock before the tick is dropped. */
        const val NO_EVIDENCE_AFTER_MS: Long = 15_000
        /** How long a clock may wait for relays that never answer before it is dropped. */
        const val GIVE_UP_AFTER_MS: Long = 90_000
        /** A quiet room's drop goes at its next slot: two slots and a minute before its clock is dropped. */
        const val QUIET_GIVE_UP_AFTER_MS: Long = (2 * QuietTransport.SLOT_SECONDS + 60) * 1000
        /** Most unsent copies of a not-kept message kept for Retry. */
        const val MAX_KEPT_EVENTS: Int = 20
        /** Settled verdicts, plus at most [MAX_KEPT_EVENTS] events of at most 64 KiB each. */
        const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024
    }
}
