package dev.forgesworn.kithmoot.session

import kotlinx.serialization.json.*

val REACTION_EMOJIS = listOf("👍", "❤️", "🤦", "😂", "🎉", "👀", "🙏", "😢")

/** An authenticated update inside encrypted kind 1460, never a public kind-7 event. */
data class ChatReaction(val messageId: String, val participant: String, val emoji: String, val active: Boolean, val revision: Int) {
    fun toJson() = buildJsonObject {
        put("messageId", messageId); put("participant", participant); put("emoji", emoji)
        put("active", active); put("revision", revision)
    }
}

fun parseReaction(value: JsonElement): ChatReaction? = runCatching {
    val r = value.jsonObject
    fun string(key: String) = r.getValue(key).jsonPrimitive.also { require(it.isString) }.content
    val id = string("messageId"); val participant = string("participant"); val emoji = string("emoji")
    val active = r.getValue("active").jsonPrimitive.also { require(!it.isString) }.boolean
    val revision = r.getValue("revision").jsonPrimitive.also { require(!it.isString) }.int
    require(id.isNotEmpty() && id.length <= 128 && participant.matches(Regex("[0-9a-fA-F]{64}")))
    require(emoji in REACTION_EMOJIS && revision >= 1)
    ChatReaction(id, participant.lowercase(), emoji, active, revision)
}.getOrNull()

/** Latest vote per sender and emoji, independent of relay order or duplicate delivery. */
fun reactionUpdates(messages: List<ChatMessage>, target: ChatMessage): List<ChatMessage> = messages
    .filter { it.reaction?.messageId == target.id && it.reaction.participant == target.participant }
    .groupBy { it.participant to it.reaction!!.emoji }
    .values.map { updates -> updates.maxWith(compareBy<ChatMessage> { it.reaction!!.revision }.thenBy { it.sentAt }.thenBy { it.id }) }

fun toggleReaction(messages: List<ChatMessage>, target: ChatMessage, self: String, emoji: String): ChatReaction {
    require(emoji in REACTION_EMOJIS)
    val old = reactionUpdates(messages, target).find { it.participant == self && it.reaction!!.emoji == emoji }?.reaction
    require(old?.revision != Int.MAX_VALUE) { "This reaction cannot be updated" }
    return ChatReaction(target.id, target.participant, emoji, old?.active != true, (old?.revision ?: 0) + 1)
}

fun reactionText(r: ChatReaction): String =
    "${if (r.active) "Reacted" else "Removed reaction"} ${r.emoji} ${if (r.active) "to" else "from"} message ${r.messageId}"
