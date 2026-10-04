package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * How far behind the reader's clock a sender's may run and still have a
 * message alert and count. A read-through is the reader's time, a message's
 * stamp the sender's, so without it a sender a few seconds slow is never
 * counted. What the room showed inside the allowance is in the inbox's seen
 * set, so the allowance never counts a message twice. A sender further
 * behind still neither alerts nor counts.
 */
const val SENDER_CLOCK_ALLOWANCE_SECONDS: Long = 300

/**
 * What the background delivery service has received for one saved room while
 * the room was closed: a cursor for the next subscription's `since`, a bounded
 * seen set so a reconnect, a second relay, a restart or a reboot never counts
 * a message twice, and the unread messages from other people. No message text
 * is kept; the room fetches it again from the relay or the box when it opens.
 *
 * Storage must be atomic and encrypted, like [PendingChatOutbox].
 */
class BackgroundInbox(
    private val storage: RoomStorage,
    private val roomId: String,
    private val participant: String,
    private val device: String,
) {
    data class Unread(val id: String, val participant: String, val sentAt: Long)
    data class State(val cursor: Long, val readThrough: Long, val seen: List<String>, val unread: List<Unread>)

    @Synchronized fun state(): State = read() ?: EMPTY

    /**
     * Records one verified message carried by the outer event [eventId]
     * created at [createdAt]. Returns true when it is new and adds an unread
     * entry: someone else's conversation message, not a reaction, edit,
     * retraction or invitation, not already seen by event or by message, and
     * sent after the room was last open, less [SENDER_CLOCK_ALLOWANCE_SECONDS].
     */
    @Synchronized fun record(eventId: String, createdAt: Long, message: ChatMessage): Boolean {
        val current = state()
        val cursor = maxOf(current.cursor, createdAt)
        if (eventId in current.seen || refOf(message).key in current.seen) {
            if (cursor != current.cursor) write(current.copy(cursor = cursor))
            return false
        }
        return add(current, listOf(eventId), cursor, createdAt, message)
    }

    /**
     * The open room alerted [message] and nobody has read it yet. Recorded as
     * seen, and unread by the same rule as [record], so a background service
     * restarted after the process died while the room was open neither
     * alerts it again nor loses it. The cursor stays where it is: the room's
     * subscription is not this one's.
     */
    @Synchronized fun recordAlerted(message: ChatMessage): Boolean {
        val current = state()
        if (refOf(message).key in current.seen) return false
        return add(current, emptyList(), current.cursor, message.sentAt, message)
    }

    private fun add(current: State, ids: List<String>, cursor: Long, createdAt: Long, message: ChatMessage): Boolean {
        val seen = (current.seen + ids + refOf(message).key).takeLast(MAX_SEEN)
        val counts = !message.participant.hexEquals(participant) && message.reaction == null &&
            message.replaces == null && message.retracts == null && message.invite == null &&
            maxOf(message.sentAt, createdAt) > current.readThrough - SENDER_CLOCK_ALLOWANCE_SECONDS
        val unread = if (counts) (current.unread + Unread(message.id, message.participant, message.sentAt)).takeLast(MAX_UNREAD)
            else current.unread
        write(State(cursor, current.readThrough, seen, unread))
        return counts
    }

    /**
     * The room is open, has just closed, or the open room has just been read,
     * at [at]: everything sent until then has been read. The next background subscription resumes a little before
     * the cursor, so without this a message shown live would count again.
     * [shown] is what the room showed: those inside the sender clock
     * allowance are seen, so they do not count either.
     */
    @Synchronized fun markRead(at: Long, shown: List<ChatMessage> = emptyList()) {
        val current = state()
        val readThrough = maxOf(current.readThrough, at)
        val keys = shown.filter { it.sentAt > readThrough - SENDER_CLOCK_ALLOWANCE_SECONDS }
            .map { refOf(it).key }.distinct().filter { it !in current.seen }
        write(current.copy(cursor = maxOf(current.cursor, at), readThrough = readThrough,
            seen = (current.seen + keys).takeLast(MAX_SEEN), unread = emptyList()))
    }

    @Synchronized fun clear() = storage.reset()

    private fun write(state: State) {
        val value = buildJsonObject {
            put("v", 1); put("room", roomId); put("participant", participant); put("device", device)
            put("cursor", state.cursor)
            put("readThrough", state.readThrough)
            put("seen", buildJsonArray { state.seen.forEach(::add) })
            put("unread", buildJsonArray { state.unread.forEach { entry -> add(buildJsonObject {
                put("id", entry.id); put("participant", entry.participant); put("sentAt", entry.sentAt)
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        try { storage.write(value) } finally { value.fill(0) }
    }

    /** A record written for another room, account or device is never used; it reads as empty. */
    private fun read(): State? {
        val bytes = storage.read() ?: return null
        try {
            require(bytes.size <= MAX_BYTES)
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root.getValue("v").jsonPrimitive.int == 1)
            if (root.text("room") != roomId || root.text("participant") != participant || root.text("device") != device) return null
            return State(
                root.getValue("cursor").jsonPrimitive.long,
                root.getValue("readThrough").jsonPrimitive.long,
                root.getValue("seen").jsonArray.map { it.jsonPrimitive.content },
                root.getValue("unread").jsonArray.map {
                    val o = it.jsonObject
                    Unread(o.text("id"), o.text("participant"), o.getValue("sentAt").jsonPrimitive.long)
                },
            )
        } finally { bytes.fill(0) }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    companion object {
        const val MAX_SEEN = 2_000
        const val MAX_UNREAD = 200
        const val MAX_BYTES = 512 * 1024
        private val EMPTY = State(0, 0, emptyList(), emptyList())
    }
}
