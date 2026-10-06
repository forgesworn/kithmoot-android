package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.MAX_EPOCH
import dev.forgesworn.kithmoot.protocol.MAX_MEMBER_EPOCH_CHAIN
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

enum class EpochPhase(val wire: String) {
    ACTIVE("active"), PENDING_CADENCE_RETIREMENT("pending-cadence-retirement"), REMOVED("removed"), CLOSED("closed");
    companion object { fun fromWire(value: String) = entries.single { it.wire == value } }
}

data class CadenceEpochCoordinate(
    val nodeId: String,
    val leaseId: String,
    val generation: Long,
    val trafficRoom: String,
    val roomGeneration: Long,
)

class PendingRoomEpoch(
    val epoch: Int,
    secret: ByteArray,
    val removed: List<String>,
    val cause: String,
    val at: Long,
    val cadence: CadenceEpochCoordinate?,
) {
    val secret = secret.copyOf()
}

class StoredRoomEpoch(
    val stableRoom: String,
    val authority: String,
    val currentEpoch: Int,
    currentSecret: ByteArray,
    val removed: List<String>,
    val phase: EpochPhase,
    val pending: PendingRoomEpoch?,
    val terminalCause: String?,
    val updatedAt: Long,
) {
    val currentSecret = currentSecret.copyOf()
}

/**
 * Monotonic, rollback-resistant room epoch journal.
 *
 * Beside it, in [history] when one is given, the vault keeps what this device
 * needs to bring another member's device up to date (`MemberEpochResponder`):
 * the secrets of the room's recent epochs and the authority's rekey into each,
 * the newest [MAX_MEMBER_EPOCH_CHAIN] of them. That store is advisory - a
 * requester checks everything it is handed against the authority's signatures
 * - so it lives apart from the journal, never holds the journal up, and an old
 * build that does not know it still reads the journal unchanged.
 */
class EpochVault(private val storage: RoomStorage, private val history: RoomStorage? = null) {
    @Synchronized fun get(stableRoom: String): StoredRoomEpoch? = read().singleOrNull { it.stableRoom == stableRoom }?.copyOut()

    @Synchronized fun initialise(stableRoom: String, authority: String, secret: ByteArray, now: Long): StoredRoomEpoch {
        validateHex(stableRoom); validateHex(authority); require(secret.size == 32 && now >= 0)
        require(deriveRoom(secret).roomId == stableRoom) { "saved room secret does not derive the stable room" }
        val records = read()
        val existing = records.singleOrNull { it.stableRoom == stableRoom }
        if (existing != null) {
            require(existing.authority == authority) { "room authority conflicts with durable state" }
            return existing.copyOut()
        }
        val record = StoredRoomEpoch(stableRoom, authority, 0, secret, emptyList(), EpochPhase.ACTIVE, null, null, now)
        write(records + record)
        return record.copyOut()
    }

    @Synchronized fun beginTransition(
        stableRoom: String,
        expectedCurrentEpoch: Int,
        notice: RekeyNotice,
        cause: String,
        cadence: CadenceEpochCoordinate?,
        now: Long,
    ): StoredRoomEpoch {
        require(notice.epoch == expectedCurrentEpoch + 1) { "a direct room update must be consecutive" }
        return beginSuccessor(stableRoom, expectedCurrentEpoch, notice, cause, cadence, now)
    }

    /** Persist an authority-proved current epoch when this device missed one or more rekeys. */
    @Synchronized fun beginCatchUp(
        stableRoom: String,
        expectedCurrentEpoch: Int,
        notice: RekeyNotice,
        cause: String,
        cadence: CadenceEpochCoordinate?,
        now: Long,
    ): StoredRoomEpoch {
        require(notice.catchUp && notice.epoch > expectedCurrentEpoch) { "a catch-up must prove a newer epoch" }
        return beginSuccessor(stableRoom, expectedCurrentEpoch, notice, cause, cadence, now)
    }

    private fun beginSuccessor(
        stableRoom: String,
        expectedCurrentEpoch: Int,
        notice: RekeyNotice,
        cause: String,
        cadence: CadenceEpochCoordinate?,
        now: Long,
    ): StoredRoomEpoch {
        val successorSecret = requireNotNull(notice.secret) { "room epoch transition has no successor secret" }
        require(successorSecret.size == 32 && notice.epoch > expectedCurrentEpoch && notice.epoch <= MAX_EPOCH)
        require(cause.matches(ID) && now >= 0)
        val records = read().toMutableList()
        val index = records.indexOfFirst { it.stableRoom == stableRoom }
        require(index >= 0) { "room epoch was not initialised" }
        val current = records[index]
        val removed = (current.removed + notice.removed.map(String::lowercase)).distinct().sorted()
        val pending = PendingRoomEpoch(notice.epoch, successorSecret, removed, cause, notice.at, cadence)
        // Already entered, with this very secret: the background listener followed the rekey
        // (see [follow]) between this device reading the journal and committing. Nothing to do,
        // and nothing to retire, since the background never follows a room with a cadence.
        if (current.phase == EpochPhase.ACTIVE && current.currentEpoch == notice.epoch && current.currentSecret.contentEquals(successorSecret)) {
            return current.copyOut()
        }
        require(current.phase == EpochPhase.ACTIVE || current.phase == EpochPhase.PENDING_CADENCE_RETIREMENT)
        require(current.currentEpoch == expectedCurrentEpoch) { "room epoch moved before transition" }
        if (current.pending != null) {
            require(samePending(current.pending, pending)) { "room epoch transition conflicts with durable state" }
            return current.copyOut()
        }
        val next = StoredRoomEpoch(
            current.stableRoom, current.authority, current.currentEpoch, current.currentSecret,
            current.removed, EpochPhase.PENDING_CADENCE_RETIREMENT, pending, null, now,
        )
        records[index] = next
        write(records)
        return next.copyOut()
    }

    /**
     * Follow the authority's rekey into the next epoch from outside an open room: the
     * background listener's commit (`BackgroundRekeyFollower`), the same transition and
     * activation an open room makes, as one step under this vault's lock. Null, and nothing
     * written, unless the room is ACTIVE exactly one epoch short of [notice]: an open room, or
     * an earlier pass, that got there first leaves it to them. Never for a room with a cadence
     * to retire, whose open room alone can retire it.
     */
    @Synchronized fun follow(stableRoom: String, notice: RekeyNotice, cause: String, now: Long): StoredRoomEpoch? {
        val current = get(stableRoom) ?: return null
        if (current.phase != EpochPhase.ACTIVE || current.currentEpoch + 1 != notice.epoch) return null
        if (notice.secret == null || notice.closed || notice.catchUp) return null
        beginTransition(stableRoom, current.currentEpoch, notice, cause, null, now)
        return activate(stableRoom, notice.epoch, now)
    }

    @Synchronized fun activate(stableRoom: String, epoch: Int, now: Long): StoredRoomEpoch {
        require(now >= 0)
        val records = read().toMutableList()
        val index = records.indexOfFirst { it.stableRoom == stableRoom }
        require(index >= 0) { "room epoch was not initialised" }
        val current = records[index]
        if (current.phase == EpochPhase.ACTIVE && current.currentEpoch == epoch) return current.copyOut()
        val pending = requireNotNull(current.pending) { "room epoch has no pending successor" }
        require(current.phase == EpochPhase.PENDING_CADENCE_RETIREMENT && pending.epoch == epoch)
        val next = StoredRoomEpoch(
            current.stableRoom, current.authority, pending.epoch, pending.secret,
            pending.removed, EpochPhase.ACTIVE, null, null, now,
        )
        records[index] = next
        write(records)
        val learnt = listOfNotNull(
            current.takeIf { it.currentEpoch > 0 }?.let { RoomEpoch(it.currentEpoch, it.currentSecret) },
            RoomEpoch(pending.epoch, pending.secret),
        )
        rememberChecked(current.stableRoom, current.authority, learnt, emptyList())
        return next.copyOut()
    }

    @Synchronized fun terminal(stableRoom: String, expectedCurrentEpoch: Int, notice: RekeyNotice, cause: String, now: Long): StoredRoomEpoch {
        require(notice.epoch == expectedCurrentEpoch + 1 && (notice.closed || notice.secret == null) && cause.matches(ID) && now >= 0)
        val records = read().toMutableList()
        val index = records.indexOfFirst { it.stableRoom == stableRoom }
        require(index >= 0) { "room epoch was not initialised" }
        val current = records[index]
        val phase = if (notice.closed) EpochPhase.CLOSED else EpochPhase.REMOVED
        val removed = (current.removed + notice.removed.map(String::lowercase)).distinct().sorted()
        if (current.phase == phase && current.currentEpoch == expectedCurrentEpoch && current.terminalCause == cause) {
            require(current.removed == removed) { "terminal room epoch conflicts with durable state" }
            return current.copyOut()
        }
        require(current.currentEpoch == expectedCurrentEpoch && current.phase == EpochPhase.ACTIVE)
        val next = StoredRoomEpoch(
            current.stableRoom, current.authority, current.currentEpoch, current.currentSecret,
            removed, phase, null, cause, now,
        )
        records[index] = next
        write(records)
        // Nothing of a room this device has left is handed on.
        forgetHistory { it != stableRoom }
        return next.copyOut()
    }

    /**
     * Forget everything kept for [stableRoom]: its journal entry and its history. The journal's
     * storage retires its Keystore key at every write, so the old version is unreadable, not
     * merely unlisted.
     */
    @Synchronized fun forget(stableRoom: String) = retain { it != stableRoom }

    /**
     * Forget every room [saved] does not name. [saved] is read under this vault's lock, and a
     * room is saved before its epoch is initialised, so a room opening meanwhile is never swept.
     */
    @Synchronized fun retainOnly(saved: () -> Set<String>) {
        val keep = saved()
        retain { it in keep }
    }

    /** Forget every room, whether or not the stores can still be read. */
    @Synchronized fun reset() {
        guarded { storage.reset() }
        try { history?.reset() } catch (_: Exception) { }
        historyCache?.values?.forEach { epochs -> epochs.values.forEach { it.secret?.fill(0) } }
        historyCache = null
    }

    private fun retain(keep: (String) -> Boolean) {
        val records = read()
        val kept = records.filter { keep(it.stableRoom) }
        if (kept.size != records.size) write(kept)
        forgetHistory(keep)
    }

    /**
     * Keep [secrets] and the authority-signed [rekeys] among them for handing
     * on later. A rekey is kept only if it is the room authority's, for this
     * room, checked as `peekRekeyEpoch` checks it; only the newest
     * [MAX_MEMBER_EPOCH_CHAIN] epochs are kept. Never throws: what cannot be
     * kept is simply not offered to anybody later.
     */
    @Synchronized fun remember(stableRoom: String, secrets: List<RoomEpoch>, rekeys: List<NostrEvent>, leftAt: Map<Int, Long> = emptyMap()) {
        val record = runCatching { get(stableRoom) }.getOrNull() ?: return
        if (record.phase == EpochPhase.REMOVED || record.phase == EpochPhase.CLOSED) return
        rememberChecked(stableRoom, record.authority, secrets, rekeys, leftAt)
    }

    /** The secret of [epoch] this device still holds: the current one, or one kept in history. */
    @Synchronized fun secretAt(stableRoom: String, epoch: Int): ByteArray? {
        if (epoch < 1) return null
        val record = runCatching { get(stableRoom) }.getOrNull() ?: return null
        if (record.phase == EpochPhase.REMOVED || record.phase == EpochPhase.CLOSED) return null
        if (record.currentEpoch == epoch) return record.currentSecret
        return readHistory()[stableRoom]?.get(epoch)?.secret?.copyOf()
    }

    /** The authority's rekey into [epoch], as this device kept it. */
    @Synchronized fun rekeyAt(stableRoom: String, epoch: Int): NostrEvent? = readHistory()[stableRoom]?.get(epoch)?.rekey

    /**
     * When the room left [epoch], unix seconds: the `created_at` of the kept rekey out of it,
     * or else the time an authority's grant gave for it (`passed`), which comes with no rekey.
     */
    @Synchronized fun leftAt(stableRoom: String, epoch: Int): Long? {
        val epochs = readHistory()[stableRoom] ?: return null
        return epochs[epoch + 1]?.rekey?.createdAt ?: epochs[epoch]?.left
    }

    private fun rememberChecked(stableRoom: String, authority: String, secrets: List<RoomEpoch>, rekeys: List<NostrEvent>, leftAt: Map<Int, Long> = emptyMap()) {
        val store = history ?: return
        try {
            val all = readHistory().toMutableMap()
            val entries = all[stableRoom]?.toMutableMap() ?: mutableMapOf()
            var changed = false
            for (value in secrets) {
                if (value.epoch < 1) continue
                val old = entries[value.epoch]
                val left = leftAt[value.epoch]?.takeIf { it >= 0 } ?: old?.left
                if (old?.secret?.contentEquals(value.secret) == true && old.left == left) continue
                entries[value.epoch] = HistoryEntry(value.secret, old?.rekey, left)
                changed = true
            }
            for (event in rekeys) {
                val epoch = peekRekeyEpoch(event, stableRoom, authority) ?: continue
                val old = entries[epoch]
                if (old?.rekey?.id == event.id) continue
                entries[epoch] = HistoryEntry(old?.secret, event, old?.left)
                changed = true
            }
            if (!changed) return
            val newest = entries.keys.max()
            entries.keys.removeAll { it <= newest - MAX_MEMBER_EPOCH_CHAIN }
            all[stableRoom] = entries
            writeHistory(store, all)
        } catch (_: Exception) {
            // Advisory: a history that cannot be written leaves this device able to
            // hand on less, never able to hand on something wrong.
        }
    }

    private fun forgetHistory(keep: (String) -> Boolean) {
        val store = history ?: return
        try {
            val all = readHistory()
            val gone = all.filterKeys { !keep(it) }
            if (gone.isEmpty()) return
            writeHistory(store, all.filterKeys(keep))
            // The cache held these for the life of the process.
            gone.values.forEach { epochs -> epochs.values.forEach { it.secret?.fill(0) } }
        } catch (_: Exception) {
            historyCache = null
        }
    }

    /** [left]: when the room left this epoch, where an authority's grant said so and no rekey out of it was kept. */
    private class HistoryEntry(secret: ByteArray?, val rekey: NostrEvent?, val left: Long? = null) {
        val secret = secret?.copyOf()
    }

    /** The history as last read or written: it is advisory, so one copy in memory is enough. */
    private var historyCache: Map<String, Map<Int, HistoryEntry>>? = null

    private fun readHistory(): Map<String, Map<Int, HistoryEntry>> {
        historyCache?.let { return it }
        val store = history ?: return emptyMap()
        val parsed = try {
            val bytes = store.read()
            if (bytes == null) emptyMap() else try {
                val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                require(root.getValue("version").jsonPrimitive.int == 1)
                root.getValue("rooms").jsonArray.associate { room ->
                    val value = room.jsonObject
                    val id = value.text("stableRoom").also(::validateHex)
                    id to value.getValue("epochs").jsonArray.associate { item ->
                        val entry = item.jsonObject
                        val epoch = entry.integer("epoch").also { require(it in 1..MAX_EPOCH) }
                        epoch to HistoryEntry(
                            entry["secret"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(::decodeSecret),
                            entry["rekey"]?.takeUnless { it == JsonNull }?.let(NostrEvent::fromJson),
                            // Absent in history an older build wrote; an older build ignores it.
                            entry["left"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long?.takeIf { it >= 0 },
                        )
                    }
                }
            } finally { bytes?.fill(0) }
        } catch (_: Exception) {
            emptyMap()
        }
        historyCache = parsed
        return parsed
    }

    private fun writeHistory(store: RoomStorage, rooms: Map<String, Map<Int, HistoryEntry>>) {
        val trimmed = rooms.mapValues { it.value.toMutableMap() }.toMutableMap()
        while (true) {
            val bytes = buildJsonObject {
                put("version", 1)
                put("rooms", JsonArray(trimmed.entries.sortedBy { it.key }.map { (id, entries) ->
                    buildJsonObject {
                        put("stableRoom", id)
                        put("epochs", JsonArray(entries.entries.sortedBy { it.key }.map { (epoch, entry) ->
                            buildJsonObject {
                                put("epoch", epoch)
                                put("secret", entry.secret?.let { JsonPrimitive(encodeSecret(it)) } ?: JsonNull)
                                put("rekey", entry.rekey?.toJson() ?: JsonNull)
                                entry.left?.let { put("left", it) }
                            }
                        }))
                    }
                }))
            }.toString().toByteArray()
            try {
                if (bytes.size <= HISTORY_MAX_BYTES) {
                    store.write(bytes)
                    historyCache = trimmed
                    return
                }
            } finally { bytes.fill(0) }
            // Over budget: drop the oldest epoch of the room keeping the most.
            val (id, entries) = trimmed.entries.filter { it.value.isNotEmpty() }.maxByOrNull { it.value.size } ?: return
            entries.remove(entries.keys.min())
            if (entries.isEmpty()) trimmed.remove(id)
        }
    }

    private fun read(): List<StoredRoomEpoch> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        try {
            require(bytes.size <= MAX_BYTES)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root.keys == setOf("version", "rooms") && root.getValue("version").jsonPrimitive.int == 1)
            root.getValue("rooms").jsonArray.map { decode(it.jsonObject) }.also { rooms ->
                require(rooms.size <= MAX_ROOMS && rooms.map { it.stableRoom }.distinct().size == rooms.size)
            }
        } finally { bytes.fill(0) }
    }

    private fun write(records: List<StoredRoomEpoch>) = guarded {
        require(records.size <= MAX_ROOMS)
        val bytes = buildJsonObject {
            put("version", 1)
            put("rooms", JsonArray(records.sortedBy { it.stableRoom }.map(::encode)))
        }.toString().toByteArray()
        try {
            require(bytes.size <= MAX_BYTES)
            storage.write(bytes)
        } finally { bytes.fill(0) }
    }

    private fun encode(value: StoredRoomEpoch): JsonObject = buildJsonObject {
        put("stableRoom", value.stableRoom); put("authority", value.authority); put("currentEpoch", value.currentEpoch)
        put("currentSecret", encodeSecret(value.currentSecret)); put("removed", strings(value.removed)); put("phase", value.phase.wire)
        put("pending", value.pending?.let(::encodePending) ?: JsonNull)
        put("terminalCause", value.terminalCause?.let(::JsonPrimitive) ?: JsonNull); put("updatedAt", value.updatedAt)
    }

    private fun encodePending(value: PendingRoomEpoch): JsonObject = buildJsonObject {
        put("epoch", value.epoch); put("secret", encodeSecret(value.secret)); put("removed", strings(value.removed))
        put("cause", value.cause); put("at", value.at); put("cadence", value.cadence?.let(::encodeCadence) ?: JsonNull)
    }

    private fun encodeCadence(value: CadenceEpochCoordinate): JsonObject = buildJsonObject {
        put("nodeId", value.nodeId); put("leaseId", value.leaseId); put("generation", value.generation)
        put("trafficRoom", value.trafficRoom); put("roomGeneration", value.roomGeneration)
    }

    private fun decode(value: JsonObject): StoredRoomEpoch {
        require(value.keys == ROOM_FIELDS)
        val pending = value["pending"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::decodePending)
        return StoredRoomEpoch(
            value.text("stableRoom"), value.text("authority"), value.integer("currentEpoch"), decodeSecret(value.text("currentSecret")),
            value.strings("removed"), EpochPhase.fromWire(value.text("phase")), pending,
            value["terminalCause"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content, value.number("updatedAt"),
        ).also(::validate)
    }

    private fun decodePending(value: JsonObject): PendingRoomEpoch {
        require(value.keys == PENDING_FIELDS)
        return PendingRoomEpoch(
            value.integer("epoch"), decodeSecret(value.text("secret")), value.strings("removed"), value.text("cause"), value.number("at"),
            value["cadence"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::decodeCadence),
        )
    }

    private fun decodeCadence(value: JsonObject): CadenceEpochCoordinate {
        require(value.keys == CADENCE_FIELDS)
        return CadenceEpochCoordinate(value.text("nodeId"), value.text("leaseId"), value.number("generation"), value.text("trafficRoom"), value.number("roomGeneration"))
    }

    private fun validate(value: StoredRoomEpoch) {
        validateHex(value.stableRoom); validateHex(value.authority); require(value.currentEpoch in 0..MAX_EPOCH && value.currentSecret.size == 32 && value.updatedAt >= 0)
        require(value.removed == value.removed.map(String::lowercase).distinct().sorted() && value.removed.all(HEX::matches))
        require((value.phase == EpochPhase.PENDING_CADENCE_RETIREMENT) == (value.pending != null))
        require((value.phase == EpochPhase.REMOVED || value.phase == EpochPhase.CLOSED) == (value.terminalCause != null))
        value.terminalCause?.let { require(it.matches(ID)) }
        value.pending?.let {
            require(it.epoch > value.currentEpoch && it.epoch <= MAX_EPOCH && it.secret.size == 32 && it.cause.matches(ID) && it.at >= 0)
            require(it.removed == it.removed.map(String::lowercase).distinct().sorted() && it.removed.containsAll(value.removed))
            it.cadence?.let { c -> require(c.nodeId.matches(NODE) && c.leaseId.matches(SHORT_ID) && c.generation > 0 && c.trafficRoom.matches(HEX) && c.roomGeneration == value.currentEpoch + 1L) }
        }
    }

    private fun samePending(a: PendingRoomEpoch, b: PendingRoomEpoch) =
        a.epoch == b.epoch && a.secret.contentEquals(b.secret) && a.removed == b.removed && a.cause == b.cause && a.at == b.at && a.cadence == b.cadence
    private fun StoredRoomEpoch.copyOut() = StoredRoomEpoch(stableRoom, authority, currentEpoch, currentSecret, removed.toList(), phase, pending?.let {
        PendingRoomEpoch(it.epoch, it.secret, it.removed.toList(), it.cause, it.at, it.cadence)
    }, terminalCause, updatedAt)

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.integer(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
    private fun encodeSecret(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decodeSecret(value: String) = Base64.getUrlDecoder().decode(value).also { require(it.size == 32) }
    private fun validateHex(value: String) = require(value.matches(HEX))
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is RoomStorageException) throw error
        throw RoomStorageException(error)
    }

    private companion object {
        const val MAX_ROOMS = 256
        const val MAX_BYTES = 1024 * 1024
        /** Under the history store's own cap; see `KithMootApplication.roomEpochs`. */
        const val HISTORY_MAX_BYTES = 4 * 1024 * 1024 - 64 * 1024
        val HEX = Regex("[0-9a-f]{64}")
        val ID = Regex("[0-9a-f]{64}")
        val SHORT_ID = Regex("[0-9a-f]{32}")
        val NODE = Regex("[a-z2-7]{52}")
        val ROOM_FIELDS = setOf("stableRoom", "authority", "currentEpoch", "currentSecret", "removed", "phase", "pending", "terminalCause", "updatedAt")
        val PENDING_FIELDS = setOf("epoch", "secret", "removed", "cause", "at", "cadence")
        val CADENCE_FIELDS = setOf("nodeId", "leaseId", "generation", "trafficRoom", "roomGeneration")
    }
}
