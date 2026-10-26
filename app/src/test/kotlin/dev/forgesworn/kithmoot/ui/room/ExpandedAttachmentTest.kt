package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.refOf
import dev.forgesworn.kithmoot.session.resolveConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpandedAttachmentTest {
    private val sender = "02".repeat(32)
    private val attachment = ChatAttachment("https://files.example.test/same", "01".repeat(32), "03".repeat(32), "same.png", "image/png")
    private fun message(id: String, at: Long) = ChatMessage(id, sender, "04".repeat(32), "", at, attachments = listOf(attachment))

    @Test fun `retracting an exact duplicate closes only its own viewer`() {
        val first = message("first", 10)
        val second = message("second", 20)
        val retraction = ChatMessage("retraction", sender, "04".repeat(32), "", 30, retracts = first.id)
        val conversation = resolveConversation(listOf(first, second, retraction))
        val firstSelection = ExpandedAttachment(refOf(first), 0, attachment)
        val secondSelection = ExpandedAttachment(refOf(second), 0, attachment)
        assertNull(expandedAttachmentMessage(firstSelection, conversation))
        assertEquals(second, expandedAttachmentMessage(secondSelection, conversation)?.original)
    }

    @Test fun `an identical attachment at another index does not keep a stale viewer`() {
        val original = message("message", 10)
        val selection = ExpandedAttachment(refOf(original), 1, attachment)
        assertNull(expandedAttachmentMessage(selection, resolveConversation(listOf(original))))
    }
}
