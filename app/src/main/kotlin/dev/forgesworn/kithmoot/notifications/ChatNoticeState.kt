package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.refOf
import dev.forgesworn.kithmoot.session.resolveConversation

/** Session-local receipts: reconnects, edits and reaction updates cannot ring again. */
class ChatNoticeState(private val since: Long, private val self: String) {
    private val seen = linkedSetOf<String>()
    private val unread = linkedMapOf<String, ChatMessage>()
    data class Update(val unread: List<ChatMessage>, val arrived: List<ChatMessage>)
    fun update(messages: List<ChatMessage>, reading: Boolean): Update {
        val resolved = resolveConversation(messages).byKey
        val valid = resolved.values.filter { !it.retracted && it.original.participant != self }
        val validKeys = valid.map { refOf(it.original).key }.toSet()
        unread.keys.retainAll(validKeys)
        val arrived = mutableListOf<ChatMessage>()
        for (entry in valid) {
            val key = refOf(entry.original).key
            if (seen.add(key) && entry.original.sentAt >= since && !reading) {
                unread[key] = entry.shown
                arrived += entry.shown
            } else if (key in unread) unread[key] = entry.shown
        }
        if (reading) unread.clear()
        while (seen.size > 5_000) seen.remove(seen.first())
        return Update(unread.values.toList(), arrived)
    }
}
