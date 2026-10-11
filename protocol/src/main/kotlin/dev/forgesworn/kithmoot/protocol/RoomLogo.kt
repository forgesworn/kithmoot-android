package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import java.math.BigDecimal
import kotlinx.serialization.json.*

/** Logo bytes travel beside this operation in credential-bound control chat. */
data class RoomLogoOp(val id: String, val at: Long, val sha256: String?, val carried: Boolean = false)

data class RoomLogoRecord(
    val image: LogoImage?, val id: String, val at: Long, val sentAt: Long, val by: String? = null,
)

/** A valid explicit removal has an image of null; an invalid payload has no result. */
data class RoomLogoPayload(val image: LogoImage?)

private const val LOGO_MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val logoId = Regex("[0-9a-f]{32}")
private val logoHash = Regex("[0-9a-f]{64}")
private val logoFields = setOf("op", "id", "at", "sha256", "carried")

private fun logoInteger(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val integer = runCatching { BigDecimal(primitive.content).toBigIntegerExact() }.getOrNull() ?: return null
    return if (integer.abs() <= LOGO_MAX_SAFE_INTEGER.toBigInteger()) integer.toLong() else null
}

fun encodeRoomLogoOp(op: RoomLogoOp): String = buildJsonObject {
    put("op", "logo"); put("id", op.id); put("at", op.at)
    put("sha256", op.sha256?.let(::JsonPrimitive) ?: JsonNull)
    if (op.carried) put("carried", true)
}.toString()

fun decodeRoomLogoOp(body: String): RoomLogoOp? = runCatching {
    val value = Json.parseToJsonElement(body) as? JsonObject ?: return null
    if (value.keys.any { it !in logoFields } || value["op"] != JsonPrimitive("logo")) return null
    val id = (value["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val at = logoInteger(value["at"]) ?: return null
    if (!logoId.matches(id) || at <= 0) return null
    val hash = when (val raw = value["sha256"]) {
        JsonNull -> null
        is JsonPrimitive -> raw.takeIf { it.isString && logoHash.matches(it.content) }?.content ?: return null
        else -> return null
    }
    val carried = when (value["carried"]) {
        null -> false
        JsonPrimitive(true) -> true
        else -> return null
    }
    RoomLogoOp(id, at, hash, carried)
}.getOrNull()

fun roomLogoOp(image: LogoImage?, at: Long, id: String = Entropy.bytes(16).toHex()): RoomLogoOp {
    val checked = image?.let { requireNotNull(LogoImages.read(it.toJson())) { "Invalid room logo image" } }
    return requireNotNull(decodeRoomLogoOp(encodeRoomLogoOp(RoomLogoOp(id, at, checked?.sha256)))) {
        "Invalid room logo change"
    }
}

/** Called only after the chat envelope's device, credential and admission checks. */
fun roomLogoPayload(body: String, value: JsonElement?, channel: String?): RoomLogoPayload? {
    if (channel != "control") return null
    val op = decodeRoomLogoOp(body) ?: return null
    if (value == JsonNull) return if (op.sha256 == null) RoomLogoPayload(null) else null
    val image = LogoImages.read(value) ?: return null
    return if (image.sha256 == op.sha256) RoomLogoPayload(image) else null
}

fun roomLogoFromMessage(
    body: String, value: JsonElement?, channel: String?, participant: String, sentAt: Long,
): RoomLogoRecord? {
    val op = decodeRoomLogoOp(body) ?: return null
    if (Math.floorDiv(op.at, 1000L) > sentAt) return null
    val payload = roomLogoPayload(body, value, channel) ?: return null
    return RoomLogoRecord(payload.image, op.id, op.at, sentAt, if (op.carried) null else participant)
}

fun compareRoomLogos(a: RoomLogoRecord, b: RoomLogoRecord): Int {
    if (a.at != b.at) return a.at.compareTo(b.at)
    val id = a.id.compareTo(b.id)
    if (id != 0) return id.coerceIn(-1, 1)
    return (a.image?.sha256 ?: "").compareTo(b.image?.sha256 ?: "").coerceIn(-1, 1)
}

private fun sameLogo(a: RoomLogoRecord, b: RoomLogoRecord) =
    a.at == b.at && a.id == b.id && a.image?.sha256 == b.image?.sha256

/** Like shared room names, writes from a removed epoch lose authority after grace.
 * The caller serialises access. A cache seed is never evidence of a live copy. */
class RoomLogoBook {
    private data class Entry(val record: RoomLogoRecord, val epoch: Int?, val cached: Boolean = false)
    private val entries = mutableListOf<Entry>()

    fun add(record: RoomLogoRecord, epoch: Int): Boolean {
        if (entries.any { !it.cached && it.epoch == epoch && it.record.sentAt == record.sentAt && sameLogo(it.record, record) }) return false
        entries += Entry(record, epoch)
        return true
    }

    fun seed(record: RoomLogoRecord, epoch: Int? = null) {
        if (epoch != null && epoch < 0 || record.sentAt !in -LOGO_MAX_SAFE_INTEGER..LOGO_MAX_SAFE_INTEGER ||
            Math.floorDiv(record.at, 1000L) > record.sentAt) return
        if (runCatching { roomLogoOp(record.image, record.at, record.id) }.isFailure) return
        entries += Entry(record, epoch, cached = true)
    }

    fun current(epoch: Int, rekeyedAt: (Int) -> Long? = { null }): RoomLogoRecord? = entries.asSequence()
        .filter { entry ->
            val written = entry.epoch
            written == null || written <= epoch && (written == epoch || rekeyedAt(written + 1)?.let {
                entry.record.at <= (it + ROOM_NAME_REKEY_GRACE_SECONDS) * 1000L
            } != false)
        }
        .map { it.record }
        .maxWithOrNull(::compareRoomLogos)

    fun carryDue(epoch: Int, now: Long, rekeyedAt: (Int) -> Long? = { null }): RoomLogoRecord? {
        val winner = current(epoch, rekeyedAt) ?: return null
        return if (entries.any { !it.cached && it.epoch == epoch && sameLogo(it.record, winner) &&
                it.record.sentAt >= now - ROOM_NAME_REPOST_SECONDS }) null else winner
    }
}
