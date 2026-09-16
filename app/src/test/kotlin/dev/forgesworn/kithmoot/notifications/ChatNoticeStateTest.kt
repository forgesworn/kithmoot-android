package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.session.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatNoticeStateTest {
    private fun message(id: String, at: Long = 100, author: String = "other") = ChatMessage(id, author, "device", "Private text", at)
    @Test fun historySelfAndReplayDoNotCreateAlerts() {
        val state = ChatNoticeState(100, "me")
        assertTrue(state.update(listOf(message("old", 99), message("mine", author = "me")), false).arrived.isEmpty())
        val all = listOf(message("old", 99), message("new"))
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
    @Test fun messagesWhileReadingAreSeenWithoutAnAlert() {
        val state = ChatNoticeState(100, "me")
        assertTrue(state.update(listOf(message("one")), true).arrived.isEmpty())
        assertTrue(state.update(listOf(message("one")), false).unread.isEmpty())
        assertEquals(1, state.update(listOf(message("one"), message("two", 101)), false).unread.size)
    }
}
