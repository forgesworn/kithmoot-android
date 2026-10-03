package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * The room's name, shared: any member may rename the room for everybody.
 *
 * A rename is a `name` op on the room's `control` chat channel - a kind-1460
 * chat event under the current epoch's control-channel id and key, signed by
 * the sender's device and carrying its credential - so a relay sees exactly
 * what it sees of every other control message:
 *
 * ```json
 * {"op":"name","name":"Book club","id":"<32 lower-case hex>","at":<unix ms>}
 * ```
 *
 * plus `"carried":true` on a copy any member posts again. Newest wins, in the
 * message order: `at`, then `id`, then the name itself, so every device that
 * holds the same renames shows the same name. Mirrors `src/room-name.ts` and
 * the `name` case of `decodeControl` in `src/control.ts` in the TypeScript
 * reference implementation; the interop vectors are the `roomName` group.
 */
data class RoomNameOp(val name: String, val id: String, val at: Long, val carried: Boolean = false)

/** A rename a reader has accepted. */
data class RoomNameRecord(
    /** Sanitised, 1 to [DisplayName.MAX_LENGTH] characters. */
    val name: String,
    /** The rename's id: 32 lower-case hex characters. */
    val id: String,
    /** When the rename was made, unix milliseconds: its order key. */
    val at: Long,
    /** Who renamed the room: the participant whose credential-bound message
     *  carried the rename. Null on a carried copy, whose sender only repeated
     *  somebody else's rename and cannot say whose it was. */
    val by: String? = null,
    /** The carrying message's `sentAt`, unix seconds. */
    val sentAt: Long,
)

/** How long a copy of the current name may sit in the control log before a
 *  member posts it again, so a newcomer reading the 30-day window still finds
 *  it. The same figure the room relays record uses. */
const val ROOM_NAME_REPOST_SECONDS: Long = 20L * 24 * 60 * 60

/** How far past the rekey that left an epoch a rename read under that epoch
 *  may claim to have been made: the chat codec's own clock-skew bound. */
const val ROOM_NAME_REKEY_GRACE_SECONDS: Long = 300

private val ROOM_NAME_ID = Regex("[0-9a-f]{32}")
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** The chat body carrying a `name` control op, keys in the reference's order. */
fun encodeRoomNameOp(op: RoomNameOp): String = buildJsonObject {
    put("op", "name")
    put("name", op.name)
    put("id", op.id)
    put("at", op.at)
    if (op.carried) put("carried", true)
}.toString()

/**
 * Decodes a `name` control op from a chat message body, or null when the text
 * is not one or breaks a rule: a name over [DisplayName.MAX_LENGTH] code points
 * is refused rather than cut, and one that sanitises to nothing is refused;
 * the id must be 32 lower-case hex characters; `at` a safe integer above zero;
 * `carried` absent or exactly `true`. What is returned carries the sanitised
 * name. Never throws: this runs on anything a relay hands over.
 */
fun decodeRoomNameOp(body: String): RoomNameOp? = runCatching {
    val m = Json.parseToJsonElement(body) as? JsonObject ?: return null
    val op = m["op"] as? JsonPrimitive ?: return null
    if (!op.isString || op.content != "name") return null
    val rawName = (m["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (rawName.codePointCount(0, rawName.length) > DisplayName.MAX_LENGTH) return null
    val name = DisplayName.sanitise(rawName) ?: return null
    val id = (m["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!ROOM_NAME_ID.matches(id)) return null
    val at = safeInteger(m["at"]) ?: return null
    if (at <= 0) return null
    val carried = when (val raw = m["carried"]) {
        null -> false
        is JsonPrimitive -> if (!raw.isString && raw.content == "true") true else return null
        else -> return null
    }
    RoomNameOp(name, id, at, carried)
}.getOrNull()

/** A JSON number that is an integer JavaScript holds exactly, or null. */
private fun safeInteger(value: Any?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = runCatching { BigDecimal(primitive.content) }.getOrNull() ?: return null
    val integral = runCatching { number.toBigIntegerExact() }.getOrNull() ?: return null
    if (integral.abs() > MAX_SAFE_INTEGER.toBigInteger()) return null
    return integral.toLong()
}

/**
 * Build a fresh rename. Throws when nothing of [name] survives sanitising: an
 * empty name is not a rename. A name over the cap is cut here, where the
 * person typing it can see the result, and refused by a reader that receives
 * one longer.
 */
fun renameRoomOp(name: String, at: Long, id: String = Entropy.bytes(16).toHex()): RoomNameOp {
    val clean = requireNotNull(DisplayName.sanitise(name)) { "a room name cannot be empty" }
    require(at in 1..MAX_SAFE_INTEGER) { "a rename needs a time" }
    return requireNotNull(decodeRoomNameOp(encodeRoomNameOp(RoomNameOp(clean, id, at)))) { "invalid rename" }
}

/** The same rename, to be posted again by any member: under a new epoch, or
 *  before its last copy leaves the retention window. Its order key is the
 *  original's, so it never outranks a later rename. */
fun carryRoomNameOp(record: RoomNameRecord): RoomNameOp = RoomNameOp(record.name, record.id, record.at, carried = true)

/**
 * Read a rename out of a decoded control-channel message, or null.
 *
 * The message has already passed the chat decoder: signed by a device whose
 * credential binds it to [participant] in this room, and admitted by the
 * room's policy. What is checked here is the op itself, and that its time is
 * no later than the second of the message carrying it - a sender cannot stamp
 * a rename in the future to pin a name above every later one.
 */
fun roomNameFromMessage(body: String, participant: String, sentAt: Long): RoomNameRecord? {
    val op = decodeRoomNameOp(body) ?: return null
    if (Math.floorDiv(op.at, 1000L) > sentAt) return null
    return RoomNameRecord(op.name, op.id, op.at, by = if (op.carried) null else participant, sentAt = sentAt)
}

/** Order two renames: by `at`, then `id` (the message rule), then the name,
 *  each compared as plain values; Kotlin compares strings by UTF-16 code
 *  unit, exactly as the reference's `<` does. */
fun compareRoomNames(a: RoomNameRecord, b: RoomNameRecord): Int {
    if (a.at != b.at) return a.at.compareTo(b.at)
    val byId = a.id.compareTo(b.id)
    if (byId != 0) return byId.coerceIn(-1, 1)
    return a.name.compareTo(b.name).coerceIn(-1, 1)
}

private fun sameRename(a: RoomNameRecord, b: RoomNameRecord): Boolean = a.id == b.id && a.at == b.at && a.name == b.name

/**
 * Every rename a device has read, with the epoch it read each under, and
 * which one is the room's name. Mirrors `RoomNameBook` in the reference.
 *
 * A rename read under an epoch the room has since left counts only if it was
 * made no later than the rekey out of that epoch plus
 * [ROOM_NAME_REKEY_GRACE_SECONDS]: after that, the only people still writing
 * under the old key are the members it removed, and a device that had not yet
 * heard of the rekey must not carry their word into the new epoch.
 *
 * Not thread-safe on its own; the caller holds the lock.
 */
class RoomNameBook {
    private class Entry(val record: RoomNameRecord, val epoch: Int?)
    private val entries = mutableListOf<Entry>()

    /** Add a rename read under [epoch]. False for one already held from the
     *  same message time, order key and sender. */
    fun add(record: RoomNameRecord, epoch: Int): Boolean {
        if (entries.any { it.epoch == epoch && it.record.sentAt == record.sentAt && sameRename(it.record, record) && it.record.by == record.by }) return false
        entries += Entry(record, epoch)
        return true
    }

    /** A rename this device accepted before and kept, read under no epoch: it
     *  counts toward the name and is never mistaken for a copy in the log. */
    fun seed(name: String, id: String, at: Long) {
        val clean = DisplayName.sanitise(name) ?: return
        if (!ROOM_NAME_ID.matches(id) || at !in 1..MAX_SAFE_INTEGER) return
        entries += Entry(RoomNameRecord(clean, id, at, sentAt = Math.floorDiv(at, 1000L)), null)
    }

    /** The room's name: the newest rename that counts, or null. [rekeyedAt]
     *  says when the authority rekeyed into an epoch, unix seconds, if known. */
    fun current(currentEpoch: Int, rekeyedAt: (Int) -> Long? = { null }): RoomNameRecord? {
        var best: RoomNameRecord? = null
        for (entry in entries) {
            val epoch = entry.epoch
            if (epoch != null && epoch < currentEpoch) {
                val left = rekeyedAt(epoch + 1)
                if (left != null && entry.record.at > (left + ROOM_NAME_REKEY_GRACE_SECONDS) * 1000L) continue
            }
            if (epoch != null && epoch > currentEpoch) continue
            if (best == null || compareRoomNames(entry.record, best) > 0) best = entry.record
        }
        return best
    }

    /** The rename to post again now, or null: the current name, when the
     *  control log of [currentEpoch] holds no copy of it, or only copies older
     *  than [ROOM_NAME_REPOST_SECONDS]. [now] is unix seconds. */
    fun carryDue(currentEpoch: Int, now: Long, rekeyedAt: (Int) -> Long? = { null }): RoomNameRecord? {
        val winner = current(currentEpoch, rekeyedAt) ?: return null
        val newest = entries.filter { it.epoch == currentEpoch && sameRename(it.record, winner) }.maxOfOrNull { it.record.sentAt }
        return if (newest == null || newest < now - ROOM_NAME_REPOST_SECONDS) winner else null
    }
}
