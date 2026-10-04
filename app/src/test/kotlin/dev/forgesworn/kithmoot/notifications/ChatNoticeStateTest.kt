package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.SENDER_CLOCK_ALLOWANCE_SECONDS
import org.junit.Assert.*
import org.junit.Test

class ChatNoticeStateTest {
    private fun message(id: String, at: Long = 100, author: String = "other") = ChatMessage(id, author, "device", "Private text", at)
    private val old = 100 - SENDER_CLOCK_ALLOWANCE_SECONDS - 1
    @Test fun historySelfAndReplayDoNotCreateAlerts() {
        val state = ChatNoticeState(100, "me")
        assertTrue(state.update(listOf(message("old", old), message("mine", author = "me")), false).arrived.isEmpty())
        val all = listOf(message("old", old), message("new"))
        assertEquals(1, state.update(all, false).arrived.size)
        assertTrue(state.update(all, false).arrived.isEmpty())
        assertEquals(1, state.update(all, false).unread.size)
        assertTrue(state.update(all, true).unread.isEmpty())
        assertTrue(state.update(all, false).unread.isEmpty())
    }
    @Test fun editingDoesNotRingAndRetractionClearsTheCount() {
        val state = ChatNoticeState(100, "me")
        val root = message("first")
        state.update(listOf(root), false)
        val edit = message("edit", 101).copy(replaces = "first", body = "Corrected")
        val update = state.update(listOf(root, edit), false)
        assertTrue(update.arrived.isEmpty()); assertEquals("Corrected", update.unread.single().body)
        val retract = message("retract", 102).copy(retracts = "first")
        assertTrue(state.update(listOf(root, edit, retract), false).unread.isEmpty())
    }
    @Test fun aCloseReadsThroughOnlyARoomThatWasBeingReadOrHasNothingUnread() {
        // Swiped away, or left from the call tab, with an alert never seen: it survives.
        assertFalse(readsThroughAtClose(reading = false, unread = 1))
        // Left while reading the chat: everything shown is read.
        assertTrue(readsThroughAtClose(reading = true, unread = 1))
        // Nothing alerted unread: reading through loses nothing.
        assertTrue(readsThroughAtClose(reading = false, unread = 0))
    }
    @Test fun countsWhatItAlertedUntilItIsRead() {
        val state = ChatNoticeState(100, "me")
        state.update(listOf(message("one"), message("two", 101)), false)
        assertEquals(2, state.unreadCount)
        state.update(listOf(message("one"), message("two", 101)), true)
        assertEquals(0, state.unreadCount)
    }
    @Test fun messagesWhileReadingAreSeenWithoutAnAlert() {
        val state = ChatNoticeState(100, "me")
        assertTrue(state.update(listOf(message("one")), true).arrived.isEmpty())
        assertTrue(state.update(listOf(message("one")), false).unread.isEmpty())
        assertEquals(1, state.update(listOf(message("one"), message("two", 101)), false).unread.size)
    }
    @Test fun reportsWhatItRead() {
        val state = ChatNoticeState(100, "me")
        val first = state.update(listOf(message("one")), false)
        assertFalse(first.read); assertEquals(listOf("one"), first.arrived.map { it.id })
        val edit = message("edit", 101).copy(replaces = "one", body = "Corrected")
        val edited = state.update(listOf(message("one"), edit), false)
        assertFalse(edited.read); assertTrue(edited.arrived.isEmpty())
        // Reading clears what was unread: read, once.
        assertTrue(state.update(listOf(message("one"), edit), true).read)
        assertFalse(state.update(listOf(message("one"), edit), true).read)
        // A message first seen while reading is read as it arrives.
        val arrived = state.update(listOf(message("one"), edit, message("two", 102)), true)
        assertTrue(arrived.read); assertTrue(arrived.arrived.isEmpty())
        assertFalse(state.update(listOf(message("one"), edit, message("two", 102)), false).read)
    }
    @Test fun aSenderWhoseClockIsBehindStillAlerts() {
        // Stamped 49 s before the room opened by a slow clock, and arriving after it.
        val state = ChatNoticeState(100, "me")
        assertEquals(listOf("slow"), state.update(listOf(message("slow", 51)), false).arrived.map { it.id })
        // Further behind than the allowance it is history.
        assertTrue(ChatNoticeState(100, "me").update(listOf(message("older", old)), false).arrived.isEmpty())
    }
    @Test fun whatTheInboxHasSeenDoesNotAlertAgain() {
        val state = ChatNoticeState(100, "me", known = listOf("other:counted"))
        assertEquals(listOf("new"), state.update(listOf(message("counted", 90), message("new", 90)), false).arrived.map { it.id })
    }
    @Test fun reportsWhatItShowedInsideTheAllowance() {
        val state = ChatNoticeState(100, "me")
        val update = state.update(listOf(message("old", old), message("recent", 90), message("mine", author = "me")), true)
        assertEquals(listOf("recent"), update.shown.map { it.id })
        assertTrue(state.update(listOf(message("old", old), message("recent", 90)), true).shown.isEmpty())
    }
}
