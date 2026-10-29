package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.SecureTimingRandom
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.protocol.DELIVERY_RECEIPT_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.MAX_DELIVERY_RECEIPT_IDS
import dev.forgesworn.kithmoot.protocol.deliveryReceiptIdHash
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
import kotlin.random.Random

/** Publishes one delivery receipt and says whether a relay took it. See [RoomSession.publishDeliveryReceipt]. */
fun interface DeliveryReceiptPublisher {
    /** True only once a relay said OK. False, or a throw, leaves the ids waiting for the next turn. */
    suspend fun publish(d: String, messageIds: List<String>, createdAt: Long, index: Int): Boolean
}

/**
 * When this device says which messages reached it: the writer half of
 * delivery receipts, for one room. A port of the web client's
 * `src/delivery-receipt-writer.ts`, rule for rule (see "Writer rules" in the
 * delivery ticks design).
 *
 * A message is acknowledged once it has been decrypted and accepted here,
 * renders as a row ([isTickMessage]), was written by somebody else, and was
 * sent no earlier than a minute before this device first saw the room and no
 * more than seven days ago. The first-seen time is the writer's own, kept with
 * what it acknowledged; never the time the room was last opened. A new device
 * of an existing person therefore says nothing about history it read; a device
 * that was away acknowledges its backlog, which is what the sender wants.
 *
 * Per stream, the first message waiting starts a timer of 2 to 6 seconds drawn
 * from the CSPRNG, and the next receipt waits at least 30 seconds after the
 * last, so a busy room costs this phone at most two receipts a minute per
 * stream. Over 256 waiting ids splits into several receipts in one flush. An
 * id is remembered as acknowledged only after a relay took the receipt naming
 * it, so a receipt that never left is retried; a duplicate is harmless.
 *
 * [storage] null keeps what was acknowledged for this visit only, as a room
 * that keeps nothing on the phone must. Never throws to its caller.
 */
class DeliveryReceiptWriter(
    private val storage: RoomStorage?,
    private val scope: CoroutineScope,
    /** Where timers fire and receipts are signed, stored and sent: off the main thread. */
    private val context: CoroutineContext = Dispatchers.IO,
    /** Milliseconds. */
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureTimingRandom(),
    /** The person's setting: off sends nothing. Asked at every flush, so a change applies at once. */
    private val enabled: () -> Boolean = { true },
) {
    private class Stream(val d: String, var publisher: DeliveryReceiptPublisher) {
        /** Waiting ids, by hash, so a message seen twice waits once. */
        val pending = LinkedHashMap<String, String>()
        /** The timer, and then the flush it starts, so [cancel] stops either. */
        var job: Job? = null
        /** When the last receipt for this stream was signed, milliseconds. */
        var lastAt: Long? = null
        var flushing = false
    }

    private val lock = Any()
    private var loaded = false
    /** When this device first saw the room, unix seconds. */
    private var firstSeen = 0L
    /** Hash to the unix second it was acknowledged. */
    private val acked = HashMap<String, Long>()
    private val streams = HashMap<String, Stream>()
    /** Bumped when the room is dropped, so a flush in flight records nothing. */
    private var generation = 0
    private var forgotten = false

    /** Read what was acknowledged now, off the main thread, rather than at the first message. */
    fun preload() = synchronized(lock) { loadLocked() }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        firstSeen = now() / 1000
        var fresh = true
        try {
            val bytes = storage?.read()
            if (bytes != null) try {
                if (bytes.size <= MAX_FILE_BYTES) {
                    val parsed = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                    (parsed?.get("s") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }?.let { firstSeen = it; fresh = false }
                    (parsed?.get("a") as? JsonArray)?.forEach { entry ->
                        val pair = entry as? JsonArray ?: return@forEach
                        val hash = (pair.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                        val at = (pair.getOrNull(1) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return@forEach
                        if (ID_HASH.matches(hash)) acked[hash] = at
                    }
                }
            } finally { bytes.fill(0) }
        } catch (_: Exception) { /* Unreadable storage: start again. Duplicates are harmless. */ }
        if (fresh) saveLocked()
    }

    private fun saveLocked() {
        val cutoff = now() / 1000 - DELIVERY_RECEIPT_TTL_SECONDS
        acked.entries.removeAll { it.value < cutoff }
        val kept = acked.entries.sortedBy { it.value }.takeLast(MAX_ACKNOWLEDGED_KEPT)
        val target = storage ?: return
        try {
            val bytes = buildJsonObject {
                put("s", firstSeen)
                put("a", buildJsonArray { kept.forEach { (hash, at) -> add(buildJsonArray { add(hash); add(at) }) } })
            }.toString().toByteArray(Charsets.UTF_8)
            try { target.write(bytes) } finally { bytes.fill(0) }
        } catch (_: Exception) { /* Kept for this visit. */ }
    }

    /**
     * A message was accepted on stream [d] in this room; [self] is this
     * device's participant and [publisher] the conversation it arrived in.
     * Never throws.
     */
    fun received(message: ChatMessage, d: String, self: String, publisher: DeliveryReceiptPublisher) {
        try {
            if (!isTickMessage(message) || message.participant.hexEquals(self)) return
            if (now() / 1000 - message.sentAt > DELIVERY_RECEIPT_TTL_SECONDS) return
            synchronized(lock) {
                if (forgotten) return
                loadLocked()
                if (message.sentAt < firstSeen - RECEIPT_JOIN_SLACK_SECONDS) return
                val hash = deliveryReceiptIdHash(message.id)
                if (hash in acked) return
                val key = d.lowercase()
                val stream = streams.getOrPut(key) { Stream(key, publisher) }
                stream.publisher = publisher
                if (stream.pending.containsKey(hash)) return
                stream.pending[hash] = message.id
                scheduleLocked(stream)
            }
        } catch (_: Exception) { /* A receipt is never worth a message. */ }
    }

    private fun scheduleLocked(stream: Stream) {
        if (stream.job != null || stream.flushing || stream.pending.isEmpty()) return
        val jitter = RECEIPT_FIRST_DELAY_MIN_MS + random.nextLong(RECEIPT_FIRST_DELAY_SPAN_MS)
        val spacing = stream.lastAt?.let { it + RECEIPT_SPACING_MS - now() } ?: 0L
        stream.job = scope.launch(context) {
            delay(maxOf(jitter, spacing))
            flush(stream)
        }
    }

    private suspend fun flush(stream: Stream) {
        val gen: Int
        val waiting: List<Pair<String, String>>
        val createdAt: Long
        val publisher: DeliveryReceiptPublisher
        synchronized(lock) {
            if (forgotten || streams[stream.d] !== stream || stream.pending.isEmpty()) { stream.job = null; return }
            if (!enabled()) { stream.pending.clear(); stream.job = null; return }
            gen = generation
            publisher = stream.publisher
            waiting = stream.pending.map { it.key to it.value }
            stream.pending.clear()
            stream.flushing = true
            val at = now()
            stream.lastAt = at
            createdAt = at / 1000
        }
        val failed = ArrayList<Pair<String, String>>()
        try {
            for ((index, chunk) in waiting.chunked(MAX_DELIVERY_RECEIPT_IDS).withIndex()) {
                val taken = try { publisher.publish(stream.d, chunk.map { it.second }, createdAt, index) }
                    // The publisher's own timeout is a receipt not taken; this flush being cancelled is not.
                    catch (e: CancellationException) { currentCoroutineContext().ensureActive(); false }
                    catch (_: Exception) { false }
                synchronized(lock) {
                    // Dropped while signing: what went out is not remembered, and nothing more goes.
                    if (generation != gen || forgotten) return
                    if (taken) { chunk.forEach { acked[it.first] = createdAt }; saveLocked() } else failed += chunk
                }
            }
        } finally {
            synchronized(lock) { stream.flushing = false }
        }
        synchronized(lock) {
            stream.job = null
            if (forgotten || generation != gen || streams[stream.d] !== stream) return
            // What did not go waits for the next turn, behind the spacing.
            for ((hash, id) in failed) if (hash !in acked) stream.pending[hash] = id
            scheduleLocked(stream)
        }
    }

    /**
     * Stop everything waiting, including a flush in flight: the room was
     * closed or left. What it had acknowledged is kept; what was waiting is
     * acknowledged on the next visit instead.
     */
    fun cancel() {
        val jobs = synchronized(lock) {
            generation++
            val running = streams.values.mapNotNull { it.job }
            streams.clear()
            running
        }
        jobs.forEach { it.cancel() }
    }

    /** [cancel], and forget what the room acknowledged, as when it is left and deleted. */
    fun forget() {
        cancel()
        synchronized(lock) {
            forgotten = true
            acked.clear()
            try { storage?.reset() } catch (_: Exception) { /* Nothing more to forget. */ }
        }
    }

    /** How many ids wait to go. For tests and a status line. */
    fun waiting(): Int = synchronized(lock) { streams.values.sumOf { it.pending.size } }

    companion object {
        const val RECEIPT_FIRST_DELAY_MIN_MS: Long = 2_000
        const val RECEIPT_FIRST_DELAY_SPAN_MS: Long = 4_000
        const val RECEIPT_SPACING_MS: Long = 30_000
        /** A message sent this long before this device first saw the room still counts: clocks differ. */
        const val RECEIPT_JOIN_SLACK_SECONDS: Long = 60
        /** Most acknowledged ids remembered per room. */
        const val MAX_ACKNOWLEDGED_KEPT: Int = 4_000
        /** [MAX_ACKNOWLEDGED_KEPT] entries of about 30 bytes, with room to spare. */
        const val MAX_FILE_BYTES: Int = 512 * 1024
        private val ID_HASH = Regex("^[0-9a-f]{16}$")
    }
}
