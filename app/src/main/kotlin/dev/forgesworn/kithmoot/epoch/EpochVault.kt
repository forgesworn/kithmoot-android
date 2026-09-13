package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.MAX_EPOCH
import dev.forgesworn.kithmoot.protocol.RekeyNotice
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

/** Monotonic, rollback-resistant room epoch journal. */
class EpochVault(private val storage: RoomStorage) {
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
        require(current.phase == EpochPhase.ACTIVE || current.phase == EpochPhase.PENDING_CADENCE_RETIREMENT)
        require(current.currentEpoch == expectedCurrentEpoch) { "room epoch moved before transition" }
        val removed = (current.removed + notice.removed.map(String::lowercase)).distinct().sorted()
        val pending = PendingRoomEpoch(notice.epoch, successorSecret, removed, cause, notice.at, cadence)
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
        return next.copyOut()
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
        val HEX = Regex("[0-9a-f]{64}")
        val ID = Regex("[0-9a-f]{64}")
        val SHORT_ID = Regex("[0-9a-f]{32}")
        val NODE = Regex("[a-z2-7]{52}")
        val ROOM_FIELDS = setOf("stableRoom", "authority", "currentEpoch", "currentSecret", "removed", "phase", "pending", "terminalCause", "updatedAt")
        val PENDING_FIELDS = setOf("epoch", "secret", "removed", "cause", "at", "cadence")
        val CADENCE_FIELDS = setOf("nodeId", "leaseId", "generation", "trafficRoom", "roomGeneration")
    }
}
