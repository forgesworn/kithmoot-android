package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.CADENCE_MAX_BODY_BYTES
import dev.forgesworn.kithmoot.protocol.CadenceReceipt
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
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

enum class CadenceOwnership(val wire: String) {
    CLIENT_EXCLUDED("client-excluded"), BOX_OWNED("box-owned"), ENDED("ended");

    companion object { fun fromWire(value: String) = entries.single { it.wire == value } }
}

data class CadenceLeasePlan(
    val nodeId: String,
    val room: String,
    val device: String,
    val leaseId: String,
    val generation: Long,
    val requestId: String,
    val requestBody: String,
    val startEpoch: Long,
    val endEpoch: Long,
    val counterLo: Int,
    val counterHi: Int,
)

data class StoredCadenceLease(
    val plan: CadenceLeasePlan,
    val ownership: CadenceOwnership,
    val receipt: CadenceReceipt?,
    val updatedAt: Long,
)

/**
 * Fail-closed ownership journal. Preparing a lease excludes its counters before
 * any network call; only a matching durable receipt transfers ownership.
 */
class CadenceLeaseVault(private val storage: RoomStorage) {
    @Synchronized fun all(room: String? = null, device: String? = null): List<StoredCadenceLease> = read()
        .filter { (room == null || it.plan.room == room) && (device == null || it.plan.device == device) }
        .sortedWith(compareBy({ it.plan.startEpoch }, { it.plan.generation }))

    @Synchronized fun prepare(plan: CadenceLeasePlan, now: Long): StoredCadenceLease {
        validate(plan, now)
        val leases = read()
        val same = leases.firstOrNull { it.plan.leaseId == plan.leaseId && it.plan.generation == plan.generation }
        if (same != null) {
            require(same.plan == plan) { "cadence request id was reused for different bytes" }
            return same
        }
        require(leases.none {
            it.ownership != CadenceOwnership.ENDED && it.plan.room == plan.room && it.plan.device == plan.device &&
                plan.startEpoch < it.plan.endEpoch && it.plan.startEpoch < plan.endEpoch &&
                plan.counterLo < it.plan.counterHi && it.plan.counterLo < plan.counterHi
        }) { "cadence lease overlaps locally excluded counters" }
        val stored = StoredCadenceLease(plan, CadenceOwnership.CLIENT_EXCLUDED, null, now)
        write(leases + stored)
        return stored
    }

    @Synchronized fun accept(current: StoredCadenceLease, receipt: CadenceReceipt, now: Long): StoredCadenceLease {
        validateReceipt(receipt)
        require(now >= 0) { "invalid cadence receipt time" }
        require(receipt.matches(current.plan)) { "cadence receipt does not match the excluded lease" }
        val leases = read()
        val index = leases.indexOfFirst { it.plan.leaseId == current.plan.leaseId && it.plan.generation == current.plan.generation }
        require(index >= 0 && leases[index] == current) { "cadence lease state changed before receipt" }
        val next = current.copy(
            ownership = if (receipt.state == "ended") CadenceOwnership.ENDED else CadenceOwnership.BOX_OWNED,
            receipt = receipt,
            updatedAt = now,
        )
        write(leases.toMutableList().apply { this[index] = next })
        return next
    }

    @Synchronized fun reservedCounters(room: String, device: String, epoch: Long): List<Int> = read()
        .asSequence()
        .filter { it.ownership != CadenceOwnership.ENDED && it.plan.room == room && it.plan.device == device && epoch in it.plan.startEpoch until it.plan.endEpoch }
        .flatMap { (it.plan.counterLo until it.plan.counterHi).asSequence() }
        .distinct().sorted().toList()

    @Synchronized fun forgetEnded(beforeEpoch: Long) = write(read().filterNot {
        it.ownership == CadenceOwnership.ENDED && it.plan.endEpoch <= beforeEpoch
    })

    private fun validate(plan: CadenceLeasePlan, now: Long) {
        require(NODE.matches(plan.nodeId) && HEX.matches(plan.room) && HEX.matches(plan.device)) { "invalid cadence lease scope" }
        require(ID.matches(plan.leaseId) && ID.matches(plan.requestId) && plan.generation > 0) { "invalid cadence lease identity" }
        require(plan.requestBody.toByteArray(Charsets.UTF_8).size <= CADENCE_MAX_BODY_BYTES) { "cadence lease body is too large" }
        require(plan.startEpoch >= 0 && plan.endEpoch > plan.startEpoch && plan.counterLo >= 0 && plan.counterHi > plan.counterLo && plan.counterHi <= 16 && now >= 0) {
            "invalid cadence lease range"
        }
    }

    private fun read(): List<StoredCadenceLease> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        try {
            require(bytes.size <= MAX_STORE_BYTES)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root.keys == setOf("version", "leases") && root.getValue("version").jsonPrimitive.int == 1)
            root.getValue("leases").jsonArray.map { decode(it.jsonObject) }.also { leases ->
                require(leases.size <= MAX_LEASES && leases.distinctBy { it.plan.leaseId to it.plan.generation }.size == leases.size)
            }
        } finally { bytes.fill(0) }
    }

    private fun write(leases: List<StoredCadenceLease>) = guarded {
        require(leases.size <= MAX_LEASES)
        val bytes = buildJsonObject {
            put("version", 1)
            put("leases", JsonArray(leases.map(::encode)))
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= MAX_STORE_BYTES)
            storage.write(bytes)
        } finally { bytes.fill(0) }
    }

    private fun encode(value: StoredCadenceLease): JsonObject = buildJsonObject {
        val p = value.plan
        put("nodeId", p.nodeId); put("room", p.room); put("device", p.device); put("leaseId", p.leaseId)
        put("generation", p.generation); put("requestId", p.requestId); put("requestBody", p.requestBody)
        put("startEpoch", p.startEpoch); put("endEpoch", p.endEpoch); put("counterLo", p.counterLo); put("counterHi", p.counterHi)
        put("ownership", value.ownership.wire); put("updatedAt", value.updatedAt)
        put("receipt", value.receipt?.let(::encodeReceipt) ?: JsonNull)
    }

    private fun decode(value: JsonObject): StoredCadenceLease {
        require(value.keys == LEASE_FIELDS)
        val plan = CadenceLeasePlan(
            value.text("nodeId"), value.text("room"), value.text("device"), value.text("leaseId"), value.number("generation"),
            value.text("requestId"), value.text("requestBody"), value.number("startEpoch"), value.number("endEpoch"),
            value.getValue("counterLo").jsonPrimitive.int, value.getValue("counterHi").jsonPrimitive.int,
        )
        val updatedAt = value.number("updatedAt")
        validate(plan, updatedAt)
        val receipt = value["receipt"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::decodeReceipt)
        val ownership = CadenceOwnership.fromWire(value.text("ownership"))
        require((ownership == CadenceOwnership.CLIENT_EXCLUDED) == (receipt == null))
        require(receipt == null || receipt.matches(plan))
        require(receipt == null || (ownership == CadenceOwnership.ENDED) == (receipt.state == "ended"))
        return StoredCadenceLease(plan, ownership, receipt, updatedAt)
    }

    private fun encodeReceipt(value: CadenceReceipt): JsonObject = buildJsonObject {
        put("code", value.code); put("leaseId", value.leaseId); put("generation", value.generation); put("state", value.state)
        put("serverTime", value.serverTime); put("startEpoch", value.startEpoch); put("endEpoch", value.endEpoch); put("queueCount", value.queueCount)
        put("sentItemIds", buildJsonArray { value.sentItemIds.forEach { add(JsonPrimitive(it)) } })
        put("failedItemIds", buildJsonArray { value.failedItemIds.forEach { add(JsonPrimitive(it)) } })
    }

    private fun decodeReceipt(value: JsonObject): CadenceReceipt {
        require(value.keys == RECEIPT_FIELDS)
        return CadenceReceipt(
            value.text("code"), value.text("leaseId"), value.number("generation"), value.text("state"), value.number("serverTime"),
            value.number("startEpoch"), value.number("endEpoch"), value.getValue("queueCount").jsonPrimitive.int,
            value.strings("sentItemIds"), value.strings("failedItemIds"),
        ).also(::validateReceipt)
    }

    private fun validateReceipt(value: CadenceReceipt) {
        require(value.code in setOf("staged", "queued", "status", "withdrawn", "stopping") && ID.matches(value.leaseId) && value.generation > 0)
        require(value.state in setOf("staged", "active", "cover", "ended"))
        require(value.serverTime >= 0 && value.startEpoch >= 0 && value.endEpoch > value.startEpoch && value.queueCount in 0..256)
        require(value.sentItemIds.size <= 256 && value.failedItemIds.size <= 256)
        require(value.sentItemIds.distinct().size == value.sentItemIds.size && value.failedItemIds.distinct().size == value.failedItemIds.size)
        require(value.sentItemIds.all(HEX::matches) && value.failedItemIds.all(HEX::matches))
        require((value.sentItemIds.toSet() intersect value.failedItemIds.toSet()).isEmpty())
    }

    private fun CadenceReceipt.matches(plan: CadenceLeasePlan) =
        leaseId == plan.leaseId && generation == plan.generation &&
            startEpoch == plan.startEpoch && endEpoch == plan.endEpoch

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is RoomStorageException) throw error
        throw RoomStorageException(error)
    }

    private companion object {
        const val MAX_LEASES = 256
        const val MAX_STORE_BYTES = 1024 * 1024
        val NODE = Regex("[a-z2-7]{52}")
        val HEX = Regex("[0-9a-f]{64}")
        val ID = Regex("[0-9a-f]{32}")
        val LEASE_FIELDS = setOf("nodeId", "room", "device", "leaseId", "generation", "requestId", "requestBody", "startEpoch", "endEpoch", "counterLo", "counterHi", "ownership", "updatedAt", "receipt")
        val RECEIPT_FIELDS = setOf("code", "leaseId", "generation", "state", "serverTime", "startEpoch", "endEpoch", "queueCount", "sentItemIds", "failedItemIds")
    }
}
