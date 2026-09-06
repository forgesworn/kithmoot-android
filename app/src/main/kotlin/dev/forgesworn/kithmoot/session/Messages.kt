package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The message layer: what a chat message may say about another message, who
 * it addresses, and how a reader turns a flat log into a conversation. A
 * port of `src/messages.ts` in the reference implementation, checked against
 * the `chatThread`, `chatEdit`, `chatRetract` and `chatMention` vectors.
 */

private val HEX_64 = Regex("[0-9a-fA-F]{64}")
const val MAX_MESSAGE_ID_LENGTH: Int = 128
const val MAX_MENTIONS: Int = 32
const val EVERYONE: String = "everyone"
const val MAX_INVITE_LINK_LENGTH: Int = 8192

fun validMessageId(id: String?): Boolean = id != null && id.isNotEmpty() && id.length <= MAX_MESSAGE_ID_LENGTH

/** A message named by its id AND its author: the pair no third party can forge. */
data class MessageRef(val messageId: String, val participant: String) {
    fun toJson(): JsonObject = buildJsonObject { put("messageId", messageId); put("participant", participant) }
    val key: String get() = "$participant:$messageId"
}

fun parseMessageRef(value: JsonElement?): MessageRef? {
    val o = value as? JsonObject ?: return null
    val id = (o["messageId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val participant = (o["participant"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!validMessageId(id) || !HEX_64.matches(participant)) return null
    return MessageRef(id, participant.normaliseHex())
}

fun refOf(m: ChatMessage): MessageRef = MessageRef(m.id, m.participant)

/** Keys and `everyone`, deduplicated and normalised; anything else dropped. */
fun normaliseMentions(raw: JsonArray): List<String> {
    val out = mutableListOf<String>()
    for (entry in raw) {
        val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        val value = when {
            text == EVERYONE -> EVERYONE
            HEX_64.matches(text) -> text.normaliseHex()
            else -> continue
        }
        if (value !in out) out.add(value)
    }
    return out
}

/** A direct-message invitation: the DM room's link sealed to one member. */
data class ChatInvite(val to: String, val room: String, val link: String) {
    fun toJson(): JsonObject = buildJsonObject { put("to", to); put("room", room); put("link", link) }
}

fun parseInvite(value: JsonElement?): ChatInvite? {
    val o = value as? JsonObject ?: return null
    fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val to = string("to") ?: return null
    val room = string("room") ?: return null
    val link = string("link") ?: return null
    if (!HEX_64.matches(to) || !HEX_64.matches(room) || link.isEmpty() || link.length > MAX_INVITE_LINK_LENGTH) return null
    return ChatInvite(to.normaliseHex(), room.normaliseHex(), link)
}

/** Whether the text names [name] as a whole word, with or without an `@`,
 *  regardless of case. The legacy reading of a mention. */
fun namesInText(text: String, name: String): Boolean {
    val wanted = name.trim()
    if (wanted.isEmpty()) return false
    val pattern = Regex("(^|[^\\p{L}\\p{N}_])@?${Regex.escape(wanted)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    return pattern.containsMatchIn(text)
}

data class Named(val participant: String, val name: String?)

/** Who a message addresses: the wire field when there is one, the roster
 *  names found in the text when there is not. */
fun mentionsOf(message: ChatMessage, roster: List<Named> = emptyList()): List<String> {
    message.mentions?.let { return it }
    val out = mutableListOf<String>()
    for (entry in roster) {
        val name = entry.name ?: continue
        if (namesInText(message.body, name) && entry.participant !in out) out.add(entry.participant)
    }
    return out
}

/** Whether the message addresses [self]. `everyone` counts for a person,
 *  never for an agent. */
fun mentionedBy(message: ChatMessage, self: String, roster: List<Named> = emptyList(), agent: Boolean = false): Boolean {
    val named = mentionsOf(message, roster)
    if (named.any { it != EVERYONE && it.hexEquals(self) }) return true
    return !agent && EVERYONE in named
}

/** Something somebody said, rather than a statement about another message. */
fun ChatMessage.isConversation(): Boolean = reaction == null && replaces == null && retracts == null && invite == null

/** A message as it should be read, once every statement about it is applied. */
data class ResolvedMessage(
    val original: ChatMessage,
    var shown: ChatMessage,
    var edited: Boolean = false,
    var edits: List<ChatMessage> = emptyList(),
    var retracted: Boolean = false,
    var thread: MessageRef? = null,
    val reply: MessageRef? = null,
    val replies: MutableList<ResolvedMessage> = mutableListOf(),
    var orphan: Boolean = false,
)

class Conversation(val stream: List<ResolvedMessage>, val byKey: Map<String, ResolvedMessage>)

/** Later wins: greater `sentAt`, then greater id. The reactions rule. */
fun later(a: ChatMessage, b: ChatMessage): Boolean = a.sentAt > b.sentAt || (a.sentAt == b.sentAt && a.id > b.id)

private val byTime = compareBy<ChatMessage> { it.sentAt }.thenBy { it.id }

/**
 * One conversation's verified messages, as a reader shows them. Edits attach
 * to their original by id and author, so an edit by anybody else is its own
 * message marked edited. A retraction hides the original whatever the times.
 * Replies nest under a loaded root, or stay in the stream marked orphan.
 */
fun resolveConversation(messages: List<ChatMessage>): Conversation {
    val sorted = messages.sortedWith(byTime)
    val byKey = LinkedHashMap<String, ResolvedMessage>()
    val editIds = LinkedHashMap<String, ChatMessage>()
    val retracted = LinkedHashSet<String>()

    for (m in sorted) {
        when {
            m.replaces != null -> editIds[refOf(m).key] = m
            m.retracts != null -> retracted.add(MessageRef(m.retracts, m.participant).key)
            m.isConversation() -> byKey[refOf(m).key] = ResolvedMessage(
                original = m, shown = m,
                thread = m.thread ?: m.reply, reply = m.reply,
            )
        }
    }

    val editsByTarget = LinkedHashMap<String, MutableList<ChatMessage>>()
    for (edit in editIds.values) {
        var target = MessageRef(edit.replaces!!, edit.participant).key
        var hops = 0
        while (hops < 8 && target !in byKey && target in editIds) {
            target = MessageRef(editIds.getValue(target).replaces!!, edit.participant).key
            hops++
        }
        editsByTarget.getOrPut(target) { mutableListOf() }.add(edit)
    }
    for ((target, edits) in editsByTarget) {
        val latest = edits.reduce { best, e -> if (later(e, best)) e else best }
        val resolved = byKey[target]
        if (resolved != null) {
            resolved.edits = edits
            resolved.edited = true
            resolved.shown = shownFrom(resolved.original, latest)
        } else {
            val key = MessageRef(latest.replaces!!, latest.participant).key
            if (key !in byKey) byKey[key] = ResolvedMessage(original = latest, shown = latest, edited = true, edits = edits)
        }
    }
    for (key in retracted) byKey[key]?.retracted = true

    val stream = mutableListOf<ResolvedMessage>()
    for (resolved in byKey.values.sortedWith(compareBy<ResolvedMessage> { it.original.sentAt }.thenBy { it.original.id })) {
        val thread = resolved.thread
        if (thread == null) { stream.add(resolved); continue }
        val root = rootOf(resolved, byKey)
        if (root != null && root !== resolved) {
            resolved.thread = refOf(root.original)
            root.replies.add(resolved)
        } else {
            resolved.orphan = true
            stream.add(resolved)
        }
    }
    return Conversation(stream, byKey)
}

private fun rootOf(resolved: ResolvedMessage, byKey: Map<String, ResolvedMessage>): ResolvedMessage? {
    var current: ResolvedMessage? = resolved
    var hops = 0
    while (hops < 8 && current?.thread != null) {
        val next = byKey[current.thread!!.key] ?: return null
        if (next === current) return null
        current = next
        hops++
    }
    return if (current?.thread != null) null else current
}

private fun shownFrom(original: ChatMessage, latest: ChatMessage): ChatMessage =
    original.copy(body = latest.body, mentions = latest.mentions)

fun retractionText(): String = "Retracted a message"
fun inviteText(): String = "Started a private conversation"
