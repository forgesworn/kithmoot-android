package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.SENDER_CLOCK_ALLOWANCE_SECONDS
import dev.forgesworn.kithmoot.session.refOf
import dev.forgesworn.kithmoot.session.resolveConversation

/**
 * Session-local receipts: reconnects, edits and reaction updates cannot ring
 * again. A message alerts if it is stamped no earlier than [since] less
 * [SENDER_CLOCK_ALLOWANCE_SECONDS]; [known] is what the background inbox has
 * already seen, so history inside the allowance does not alert again.
 */
class ChatNoticeState(private val since: Long, private val self: String, known: Collection<String> = emptyList()) {
    private val seen = linkedSetOf<String>().apply { addAll(known) }
    private val unread = linkedMapOf<String, ChatMessage>()
    /**
     * [arrived] is what alerts now. [read] is true when this update read
     * something: unread cleared, or a message first seen while reading.
     * [shown] is what was first seen while reading, inside the allowance.
     */
    data class Update(val unread: List<ChatMessage>, val arrived: List<ChatMessage>, val read: Boolean = false,
        val shown: List<ChatMessage> = emptyList())
    fun update(messages: List<ChatMessage>, reading: Boolean): Update {
        val resolved = resolveConversation(messages).byKey
        val valid = resolved.values.filter { !it.retracted && it.original.participant != self }
        val validKeys = valid.map { refOf(it.original).key }.toSet()
        unread.keys.retainAll(validKeys)
        val arrived = mutableListOf<ChatMessage>()
        val shown = mutableListOf<ChatMessage>()
        var read = reading && unread.isNotEmpty()
        for (entry in valid) {
            val key = refOf(entry.original).key
            val first = seen.add(key)
            val fresh = entry.original.sentAt >= since - SENDER_CLOCK_ALLOWANCE_SECONDS
            if (first && reading) read = true
            if (first && reading && fresh) shown += entry.original
            if (first && fresh && !reading) {
                unread[key] = entry.shown
                arrived += entry.shown
            } else if (key in unread) unread[key] = entry.shown
        }
        if (reading) unread.clear()
        while (seen.size > 5_000) seen.remove(seen.first())
        return Update(unread.values.toList(), arrived, read, shown)
    }
    /** Everything unread so far is read, as when the person replies from the notification. */
    fun read() = unread.clear()
    /** Messages alerted here and not yet read. */
    val unreadCount: Int get() = unread.size
}

/**
 * Whether closing a room reads it through: only if it was being read (on
 * screen, in the foreground) when it closed, or holds nothing unread. An
 * alert the person never saw survives the close, whether by a back press from
 * the call or by swiping the app away (P4-02, decided 3 October 2026).
 */
internal fun readsThroughAtClose(reading: Boolean, unread: Int): Boolean = reading || unread == 0
