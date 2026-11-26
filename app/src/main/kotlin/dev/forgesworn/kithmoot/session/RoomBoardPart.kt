package dev.forgesworn.kithmoot.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The room's own board on the wire, as `src/room-board-wire.ts` in KithMoot
 * has it.
 *
 * Every room has one board, a Yjs document carried in the room's channel
 * `board` under the room's current epoch, exactly as chat is. Its changes are
 * opaque bytes here: a [BoardPart] is one piece of one blob of them, the
 * single statement of a chat envelope, which is what authenticates the device
 * and the participant that sent it. This app never reads the bytes; the board
 * itself runs in the room board page, which holds no key.
 */
const val ROOM_BOARD_CHANNEL: String = "board"

/** Bytes of a blob one envelope carries: 22 KiB. */
const val MAX_BOARD_PART_BYTES: Int = 22 * 1024
/** Parts in one blob. */
const val MAX_BOARD_PARTS: Int = 96
/** Base64 characters of one part's bytes, at most. */
private const val MAX_BOARD_PART_CHARS: Int = (MAX_BOARD_PART_BYTES + 2) / 3 * 4

private val PART_KEYS = listOf("blob", "count", "data", "index", "kind", "v")
private val BLOB_ID = Regex("[0-9a-f]{32}")
private val BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
private const val MAX_SAFE_INTEGER: Double = 9_007_199_254_740_991.0

/** One part of one blob of the board's changes: `update` is some changes, `snapshot` the sender's whole board. */
data class BoardPart(val kind: String, val blob: String, val index: Int, val count: Int, val data: String) {
    /** In the key order KithMoot writes: `v`, `kind`, `blob`, `index`, `count`, `data`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("kind", kind)
        put("blob", blob)
        put("index", index)
        put("count", count)
        put("data", data)
    }
}

/** A JSON number that is an integer a JavaScript reader takes as one exactly, or null. */
private fun safeInteger(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.content.toDoubleOrNull() ?: return null
    if (number.isNaN() || number.isInfinite() || number != Math.floor(number) || Math.abs(number) > MAX_SAFE_INTEGER) return null
    return number.toLong()
}

/**
 * [value] as a board part in its one honest shape, or null: exactly these
 * fields and nothing else, every bound kept. Never throws. Mirrors
 * `validBoardPart` in KithMoot `src/room-board-wire.ts`.
 */
fun parseBoardPart(value: JsonElement?): BoardPart? {
    val part = value as? JsonObject ?: return null
    if (part.keys.sorted() != PART_KEYS) return null
    if (safeInteger(part["v"]) != 1L) return null
    val kind = (part["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (kind != "update" && kind != "snapshot") return null
    val blob = (part["blob"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!BLOB_ID.matches(blob)) return null
    val count = safeInteger(part["count"]) ?: return null
    if (count < 1 || count > MAX_BOARD_PARTS) return null
    val index = safeInteger(part["index"]) ?: return null
    if (index < 0 || index >= count) return null
    val data = (part["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (data.isEmpty() || data.length > MAX_BOARD_PART_CHARS || !BASE64.matches(data)) return null
    return BoardPart(kind, blob, index.toInt(), count.toInt(), data)
}
