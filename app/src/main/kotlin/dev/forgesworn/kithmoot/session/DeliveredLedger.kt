package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.protocol.DELIVERY_RECEIPT_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.DeliveryReceipt
import dev.forgesworn.kithmoot.protocol.deliveryReceiptIdHash
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext

/** One person whose device confirmed a message. */
data class DeliveredBy(
    val participant: String,
    /** The receipt's `created_at`, unix seconds: when that device confirmed. */
    val at: Long,
    val agent: Boolean,
)

/**
 * Who has confirmed this person's own messages in one room: the two ticks. A
 * port of the web client's `DeliveredLedger` (`app/src/delivery-receipts.ts`).
 *
 * Receipts (kind 1465, `protocol/DeliveryReceipt.kt`) name messages by a short
 * hash of their id. This keeps which of this person's own messages each other
 * person's device has confirmed, and when; matches a receipt only against this
 * person's own messages (anything else in it is somebody else's business and
 * dropped); and says how far back relays are worth asking.
 *
 * Delivery is per person: a person counts once whichever of their devices
 * confirmed. This person's own devices never count. Kept on the phone for seven
 * days, as long as a receipt lives on a relay. [storage] null keeps it for this
 * visit only.
 *
 * With a [scope], writes are gathered and made about a second later on
 * [context], off the main thread: a room opening replays every recent message
 * and receipt at once, and one encrypted write each would hold the screen.
 * [flush] writes what is waiting now, as the room closes. Without one, each
 * change is written at once.
 */
class DeliveredLedger(
    private val storage: RoomStorage?,
    /** Milliseconds. */
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope? = null,
    private val context: CoroutineContext = Dispatchers.IO,
) {
    private class Own(val id: String, val sentAt: Long)
    private class Entry(val sentAt: Long, val by: LinkedHashMap<String, DeliveredBy> = LinkedHashMap())

    private val lock = Any()
    /** Own message hash to id and send time, rebuilt from the log each visit. */
    private val own = HashMap<String, Own>()
    /** Own message id to who confirmed it. Persisted. */
    private val delivered = LinkedHashMap<String, Entry>()
    /** Confirmations for hashes not (yet) known to be our own. Memory only. */
    private val unmatched = LinkedHashMap<String, MutableList<DeliveredBy>>()
    /** The newest receipt read, unix seconds. Persisted: the next visit asks relays from here. */
    private var newest: Long? = null
    /** The oldest of this person's messages seen in the last seven days, unix seconds. Persisted. */
    private var oldestOwn: Long? = null
    private var forgotten = false
    /** A change not yet written, and the write gathered for it. */
    private var dirty = false
    private var pendingWrite: Job? = null

    private val _state = MutableStateFlow<Map<String, List<DeliveredBy>>>(emptyMap())
    /** Every own message somebody confirmed, by message id, earliest confirmation first. */
    val state: StateFlow<Map<String, List<DeliveredBy>>> = _state.asStateFlow()

    init {
        synchronized(lock) {
            load()
            pruneLocked()
            publishLocked()
        }
    }

    private fun load() {
        try {
            val bytes = storage?.read() ?: return
            try {
                if (bytes.size > MAX_FILE_BYTES) return
                val parsed = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: return
                newest = (parsed["t"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                oldestOwn = (parsed["o"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                (parsed["m"] as? JsonArray)?.forEach { raw ->
                    val entry = raw as? JsonArray ?: return@forEach
                    val id = (entry.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                    val sentAt = (entry.getOrNull(1) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return@forEach
                    val who = entry.getOrNull(2) as? JsonArray ?: return@forEach
                    val by = LinkedHashMap<String, DeliveredBy>()
                    for (w in who) {
                        val row = w as? JsonArray ?: continue
                        val participant = (row.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                        val at = (row.getOrNull(1) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: continue
                        if (!HEX64.matches(participant)) continue
                        val agent = (row.getOrNull(2) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull == 1L
                        by[participant] = DeliveredBy(participant, at, agent)
                    }
                    if (by.isNotEmpty()) delivered[id] = Entry(sentAt, by)
                }
            } finally { bytes.fill(0) }
        } catch (_: Exception) {
            // Unreadable storage: what relays still hold comes back.
            delivered.clear(); newest = null; oldestOwn = null
        }
    }

    /** Drop what is a week old, and keep at most [MAX_DELIVERED_KEPT] messages, the newest. */
    private fun pruneLocked() {
        val cutoff = now() / 1000 - DELIVERY_RECEIPT_TTL_SECONDS
        delivered.entries.removeAll { it.value.sentAt < cutoff }
        if ((oldestOwn ?: Long.MAX_VALUE) < cutoff) oldestOwn = null
        if (delivered.size > MAX_DELIVERED_KEPT) {
            val keep = delivered.entries.sortedBy { it.value.sentAt }.takeLast(MAX_DELIVERED_KEPT).mapTo(HashSet()) { it.key }
            delivered.keys.retainAll(keep)
        }
    }

    private fun saveLocked() {
        pruneLocked()
        val gather = scope ?: return writeLocked()
        dirty = true
        if (pendingWrite != null) return
        pendingWrite = gather.launch(context) {
            delay(SAVE_DELAY_MS)
            synchronized(lock) {
                pendingWrite = null
                if (dirty && !forgotten) { dirty = false; writeLocked() }
            }
        }
    }

    /** Write what is waiting now: the room is closing. */
    fun flush() = synchronized(lock) {
        pendingWrite?.cancel(); pendingWrite = null
        if (dirty && !forgotten) { dirty = false; writeLocked() }
    }

    private fun writeLocked() {
        val kept = delivered.entries.sortedBy { it.value.sentAt }
        val target = storage ?: return
        try {
            if (kept.isEmpty() && newest == null && oldestOwn == null) { target.reset(); return }
            val bytes = buildJsonObject {
                newest?.let { put("t", it) }
                oldestOwn?.let { put("o", it) }
                put("m", buildJsonArray {
                    for ((id, entry) in kept) add(buildJsonArray {
                        add(id); add(entry.sentAt)
                        add(buildJsonArray {
                            for (w in entry.by.values) add(buildJsonArray { add(w.participant); add(w.at); if (w.agent) add(1) })
                        })
                    })
                })
            }.toString().toByteArray(Charsets.UTF_8)
            try { if (bytes.size <= MAX_FILE_BYTES) target.write(bytes) } finally { bytes.fill(0) }
        } catch (_: Exception) { /* Shown for this visit. */ }
    }

    private fun publishLocked() {
        _state.value = delivered.mapValues { (_, entry) -> entry.by.values.sortedBy { it.at } }
    }

    /** One of this person's own messages, from any of their devices, as the log accepts it. */
    fun own(message: ChatMessage) = synchronized(lock) {
        if (forgotten) return@synchronized
        val hash = deliveryReceiptIdHash(message.id)
        if (hash in own) return@synchronized
        own[hash] = Own(message.id, message.sentAt)
        val floor = now() / 1000 - DELIVERY_RECEIPT_TTL_SECONDS
        val older = message.sentAt >= floor && (oldestOwn == null || message.sentAt < oldestOwn!!)
        if (older) oldestOwn = message.sentAt
        val waiting = unmatched.remove(hash)
        if (waiting == null) { if (older) saveLocked(); return@synchronized }
        var changed = false
        for (who in waiting) changed = recordLocked(message.id, message.sentAt, who) || changed
        if (changed || older) saveLocked()
        if (changed) publishLocked()
    }

    private fun recordLocked(id: String, sentAt: Long, who: DeliveredBy): Boolean {
        val entry = delivered.getOrPut(id) { Entry(sentAt) }
        val before = entry.by[who.participant]
        if (before == null) { entry.by[who.participant] = who; return true }
        // The first confirmation says when it arrived; an agent is an agent once any of its devices says so.
        val next = DeliveredBy(who.participant, minOf(before.at, who.at), before.agent || who.agent)
        if (next == before) return false
        entry.by[who.participant] = next
        return true
    }

    /** A receipt that passed every check. [self] is this device's person, whose receipts never count. */
    fun receipt(receipt: DeliveryReceipt, self: String) = synchronized(lock) {
        if (forgotten || receipt.participant.hexEquals(self)) return@synchronized
        val who = DeliveredBy(receipt.participant.lowercase(), receipt.createdAt, receipt.agent)
        var changed = false
        for (hash in receipt.got) {
            val mine = own[hash]
            if (mine != null) { changed = recordLocked(mine.id, mine.sentAt, who) || changed; continue }
            val list = unmatched.getOrPut(hash) { mutableListOf() }
            if (list.none { it.participant == who.participant }) list += who
            while (unmatched.size > MAX_UNMATCHED) unmatched.remove(unmatched.keys.first())
        }
        var stamped = false
        if (newest == null || receipt.createdAt > newest!!) { newest = receipt.createdAt; stamped = true }
        if (changed || stamped) saveLocked()
        if (changed) publishLocked()
    }

    /** Who has confirmed one of this person's messages, earliest first. */
    fun delivered(messageId: String): List<DeliveredBy> = _state.value[messageId].orEmpty()

    /**
     * The oldest receipt worth asking relays for, unix seconds. After a visit
     * that read receipts: from the newest one read, less some slack, since a
     * receipt is stamped when it is published. Before any: from this person's
     * oldest recent message, less a minute. With neither, from now: live
     * receipts still arrive. Never older than seven days.
     */
    fun since(): Long = synchronized(lock) {
        val at = now() / 1000
        val floor = at - DELIVERY_RECEIPT_TTL_SECONDS
        newest?.let { return@synchronized maxOf(floor, minOf(at, it - RECEIPT_REFETCH_SLACK_SECONDS)) }
        oldestOwn?.let { maxOf(floor, minOf(at, it - 60)) } ?: at
    }

    /** Forget the room, as when it is left and deleted. */
    fun forget() = synchronized(lock) {
        forgotten = true
        pendingWrite?.cancel(); pendingWrite = null; dirty = false
        own.clear(); delivered.clear(); unmatched.clear(); newest = null; oldestOwn = null
        try { storage?.reset() } catch (_: Exception) { /* Nothing more to forget. */ }
        publishLocked()
    }

    companion object {
        /** Most own messages whose confirmations are kept for a room. */
        const val MAX_DELIVERED_KEPT: Int = 200
        /** Slack on the receipts asked for again after a visit: relays are not instant. */
        const val RECEIPT_REFETCH_SLACK_SECONDS: Long = 600
        /** Most unmatched hashes held in memory while this person's own messages load. */
        const val MAX_UNMATCHED: Int = 2_000
        /** How long changes are gathered before they are written. */
        const val SAVE_DELAY_MS: Long = 1_000
        /** [MAX_DELIVERED_KEPT] messages with a few dozen confirmations each, with room to spare. */
        const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/** What the two ticks on one message rest on. */
data class DeliveredView(
    /** People (not agents) whose devices confirmed, earliest first. */
    val people: List<DeliveredBy>,
    val agents: List<DeliveredBy>,
    /** Members of a fixed list who have not confirmed yet, when there is a fixed list. */
    val waiting: List<String>? = null,
    /** The fixed list's size, less this person, when there is one. */
    val of: Int? = null,
)

/**
 * Whether a message shows two ticks, and the facts its words come from. Null
 * when nobody who counts has confirmed it.
 *
 * - [members]: the room's fixed member list (`RoomPolicy.members`), when it has one.
 * - [knownPeople]: whether anybody but agents is known in the room besides this
 *   person (seen in the roster, wrote in the log, or confirmed). An agent's
 *   confirmation lights the tick only when nobody else is.
 */
fun deliveredView(delivered: List<DeliveredBy>, self: String, members: List<String>?, knownPeople: Boolean): DeliveredView? {
    val others = delivered.filter { !it.participant.hexEquals(self) }
    val people = others.filter { !it.agent }
    val agents = others.filter { it.agent }
    if (people.isEmpty() && (agents.isEmpty() || knownPeople)) return null
    if (members == null) return DeliveredView(people, agents)
    val list = members.map { it.lowercase() }.distinct().filter { !it.hexEquals(self) }
    val confirmed = others.mapTo(HashSet()) { it.participant.lowercase() }
    return DeliveredView(people, agents, waiting = list.filter { it !in confirmed }, of = list.size)
}

/** Who besides this person is known in the room. */
data class DeliveryAudience(val knownPeople: Boolean, val knownOthers: Int)

/**
 * Who besides this person is known in the room on screen: the roster (with
 * whether each says it is an agent), the log's authors and whoever confirmed.
 * Agents apart, since an agent's confirmation lights the tick only when nobody
 * else is known. The web's `deliveryAudience`.
 */
fun deliveryAudience(self: String, roster: List<Pair<String, Boolean>>, authors: Collection<String>, delivered: List<DeliveredBy>): DeliveryAudience {
    val me = self.lowercase()
    val agents = HashSet<String>()
    val people = HashSet<String>()
    for ((participant, agent) in roster) participant.lowercase().takeIf { it != me }?.let { if (agent) agents += it else people += it }
    for (w in delivered) w.participant.lowercase().takeIf { it != me }?.let { if (w.agent) agents += it else people += it }
    for (author in authors) author.lowercase().takeIf { it != me && it !in agents }?.let { people += it }
    people.removeAll(agents)
    return DeliveryAudience(people.isNotEmpty(), people.size + agents.size)
}

/** The two ticks on one of this person's messages: what they rest on, and how many others are known in the room. */
data class DeliveredTick(val view: DeliveredView, val knownOthers: Int)

/**
 * Which of this person's messages show two ticks, by message id, from who
 * confirmed each ([confirmed], the ledger's state), the roster (participant and
 * whether it says it is an agent), the log and the room's fixed member list
 * when it has one. A message nobody who counts has confirmed is absent.
 */
fun deliveredTicks(
    confirmed: Map<String, List<DeliveredBy>>,
    self: String,
    roster: List<Pair<String, Boolean>>,
    log: List<ChatMessage>,
    members: List<String>?,
): Map<String, DeliveredTick> {
    if (confirmed.isEmpty()) return emptyMap()
    val authors = log.mapTo(HashSet()) { it.participant }
    val out = HashMap<String, DeliveredTick>()
    for ((id, by) in confirmed) {
        if (by.isEmpty()) continue
        val audience = deliveryAudience(self, roster, authors, by)
        val view = deliveredView(by, self, members, audience.knownPeople) ?: continue
        out[id] = DeliveredTick(view, audience.knownOthers)
    }
    return out
}
