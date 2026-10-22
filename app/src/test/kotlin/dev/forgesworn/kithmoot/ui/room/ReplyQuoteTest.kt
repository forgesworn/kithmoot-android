package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.ChatArtwork
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.MessageRef
import dev.forgesworn.kithmoot.session.refOf
import dev.forgesworn.kithmoot.session.replyRefsFor
import dev.forgesworn.kithmoot.session.resolveConversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Replies in time order with a quote, as the web's reply-quote.test.ts (kithmoot #506). */
class ReplyQuoteTest {
    private val ada = "a1".repeat(32)
    private val rowan = "b2".repeat(32)
    private fun message(id: String, body: String, at: Long, who: String = ada, reply: MessageRef? = null, thread: MessageRef? = null,
        attachments: List<ChatAttachment> = emptyList(), artwork: List<ChatArtwork> = emptyList()) =
        ChatMessage(id, who, who, body, at, reply = reply, thread = thread, attachments = attachments, artwork = artwork)
    private fun file(type: String?, name: String? = null) = ChatAttachment("https://example.org/x", "00".repeat(32), "11".repeat(32), name = name, type = type)

    @Test fun `a quote is one line of the words, cut on a character boundary`() {
        assertEquals("Agenda for Thursday is in the doc", quoteSnippet(message("m", "Agenda for\n  Thursday   is in the doc ", 1)))
        assertEquals("word ".repeat(32).trimEnd() + "…", quoteSnippet(message("m", "word ".repeat(60), 1)))
        // A pair of UTF-16 units is never split.
        val emoji = quoteSnippet(message("m", "a".repeat(159) + "😀😀", 1))
        assertEquals("a".repeat(159) + "…", emoji)
    }

    @Test fun `with no words a quote names what was sent, in the web's words`() {
        assertEquals("Photo", quoteSnippet(message("m", "", 1, attachments = listOf(file("image/jpeg")))))
        assertEquals("Audio", quoteSnippet(message("m", "", 1, attachments = listOf(file("audio/ogg")))))
        assertEquals("Video", quoteSnippet(message("m", "", 1, attachments = listOf(file("video/mp4")))))
        assertEquals("File: plan.pdf", quoteSnippet(message("m", "", 1, attachments = listOf(file("application/pdf", "plan.pdf")))))
        assertEquals("File: Encrypted file", quoteSnippet(message("m", "", 1, attachments = listOf(file(null)))))
        assertEquals("Sticker", quoteSnippet(message("m", "", 1, artwork = listOf(ChatArtwork("kithmoot", "wave", "sticker", "22".repeat(32), "Wave")))))
        assertEquals("", quoteSnippet(message("m", "", 1)))
    }

    @Test fun `a reply quotes its parent's latest words, or says it was deleted or is not here`() {
        val root = message("root", "Agenda for Thursday", 10)
        val edit = ChatMessage("e", ada, ada, "Agenda for Friday", 11, replaces = "root")
        val reply = message("r1", "I'll bring the projector", 20, rowan, reply = refOf(root), thread = refOf(root))
        val conversation = resolveConversation(listOf(root, edit, reply))
        val quote = assertIs<ReplyQuote.Ready>(replyQuoteOf(conversation.byKey.getValue(refOf(reply).key), conversation.byKey))
        assertEquals("Agenda for Friday", quote.snippet)
        assertEquals(refOf(root), quote.ref)
        assertNull(replyQuoteOf(conversation.byKey.getValue(refOf(root).key), conversation.byKey))

        val retraction = ChatMessage("x", ada, ada, "Retracted a message", 30, retracts = "root")
        val gone = resolveConversation(listOf(root, reply, retraction))
        assertEquals(QUOTE_DELETED, replyQuoteOf(gone.byKey.getValue(refOf(reply).key), gone.byKey)!!.words())

        val alone = resolveConversation(listOf(reply))
        val missing = assertIs<ReplyQuote.Missing>(replyQuoteOf(alone.byKey.getValue(refOf(reply).key), alone.byKey))
        assertEquals(QUOTE_MISSING, missing.words())
    }

    @Test fun `a deeper reply quotes the message it answers, and one naming only a thread quotes the root`() {
        val root = message("root", "Who has the keys?", 10)
        val first = message("r1", "Rowan does", 20, rowan, reply = refOf(root), thread = refOf(root))
        val deeper = message("r2", "Not since Tuesday", 30, ada, reply = refOf(first), thread = refOf(root))
        val agent = message("r3", "Logged it", 40, rowan, thread = refOf(root))
        val conversation = resolveConversation(listOf(root, first, deeper, agent))
        assertEquals("Rowan does", assertIs<ReplyQuote.Ready>(replyQuoteOf(conversation.byKey.getValue(refOf(deeper).key), conversation.byKey)).snippet)
        assertEquals("Who has the keys?", assertIs<ReplyQuote.Ready>(replyQuoteOf(conversation.byKey.getValue(refOf(agent).key), conversation.byKey)).snippet)
    }

    @Test fun `a reply names the message and its thread, as the library does`() {
        val root = message("root", "Who has the keys?", 10)
        assertEquals(refOf(root) to refOf(root), replyRefsFor(root))
        val first = message("r1", "Rowan does", 20, rowan, reply = refOf(root), thread = refOf(root))
        assertEquals(refOf(first) to refOf(root), replyRefsFor(first))
        // A reply sent with only `reply` is in its parent's thread.
        val bare = message("r2", "Since Tuesday", 30, ada, reply = refOf(root))
        assertEquals(refOf(bare) to refOf(root), replyRefsFor(bare))
    }

    @Test fun `the quote's button says where it goes`() {
        assertEquals("Go to Rowan's message: Agenda", quoteGoLabel("Rowan", mine = false, words = "Agenda"))
        assertEquals("Go to your message: Agenda", quoteGoLabel("You", mine = true, words = "Agenda"))
    }
}
