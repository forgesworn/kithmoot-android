package dev.forgesworn.kithmoot.notifications

import dev.forgesworn.kithmoot.session.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class NoticeContentTest {
    private fun line(id: String, at: Long, body: String = "Hello $id") = NoticeLine(id, "morgs-key", "Morgs", body, at)

    @Test fun aPrivateConversationIsTitledByItsSender() {
        val content = noticeContent("Morgs and me", private = true, unread = listOf(line("a", 1)), previews = true)
        assertNull(content.title)
        assertEquals("Hello a", content.lines.single().body)
    }

    @Test fun aRoomIsTitledByItsName() {
        assertEquals("The Moot", noticeContent("The Moot", private = false, unread = listOf(line("a", 1)), previews = true).title)
        assertEquals("KithMoot", noticeContent("", private = false, unread = listOf(line("a", 1)), previews = true).title)
    }

    @Test fun withoutPreviewsNoTextLeavesTheApp() {
        val content = noticeContent("The Moot", private = false, unread = listOf(line("a", 1, "Secret plans")), previews = false)
        assertEquals("New message", content.lines.single().body)
        assertEquals("Morgs", content.lines.single().sender)
    }

    @Test fun showsTheLatestFewOldestFirstButCountsThemAll() {
        val unread = (10 downTo 1).map { line("m$it", it.toLong()) }
        val content = noticeContent("The Moot", private = false, unread = unread, previews = true)
        assertEquals(10, content.unread)
        assertEquals(MAX_NOTICE_LINES, content.lines.size)
        assertEquals((5..10).map { "m$it" }, content.lines.map { it.id })
    }

    @Test fun aSenderWithoutANameIsNamedByTheirKey() {
        val message = ChatMessage("id", "abcdef0123456789abcdef", "device", "x".repeat(1_000), 1)
        val line = noticeLine(message)
        assertEquals("abcdef012345", line.sender)
        assertEquals(MAX_NOTICE_BODY, line.body.length)
        assertEquals("Morgs", noticeSender(message.copy(name = "Morgs")))
        assertEquals("abcdef012345", noticeSender(message.copy(name = "  ")))
    }
}
