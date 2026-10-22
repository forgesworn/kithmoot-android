package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.MessageRef
import dev.forgesworn.kithmoot.session.ResolvedMessage
import dev.forgesworn.kithmoot.session.artworkMessageText

/**
 * What the quote inside a reply's bubble says about the message it answers,
 * as the web's app/src/reply-quote.ts (kithmoot #506).
 *
 * A reply is an ordinary message in the log, in time order. The wire still
 * names a parent (`reply`) and a root (`thread`); the quote uses the parent,
 * or the root when only that was sent, which for a reply to the root is the
 * same message.
 */
sealed interface ReplyQuote {
    val ref: MessageRef

    /** The original is loaded and shown: say who, and what, and offer the jump. */
    data class Ready(override val ref: MessageRef, val target: ResolvedMessage, val snippet: String) : ReplyQuote

    /** The author retracted it. Nothing of it is quoted. */
    data class Deleted(override val ref: MessageRef, val target: ResolvedMessage) : ReplyQuote

    /** Older than the history loaded here, or never received. */
    data class Missing(override val ref: MessageRef) : ReplyQuote
}

const val QUOTE_DELETED = "Message deleted"
const val QUOTE_MISSING = "Original message not loaded"

/** The longest quote worth building; the bubble ellipsises it to its width. */
private const val SNIPPET_LENGTH = 160

/** One line of somebody's words: line breaks and runs of space collapsed, cut on a character boundary. */
private fun oneLine(text: String): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    if (flat.length <= SNIPPET_LENGTH) return flat
    var cut = flat.take(SNIPPET_LENGTH)
    if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut.trimEnd() + "…"
}

/**
 * The words to quote: [message]'s current text on one line or, with no
 * text, what kind of thing it carried, in the app's own names for them.
 * A sticker's fallback text is not words, so it reads as a sticker.
 */
fun quoteSnippet(message: ChatMessage): String {
    val line = oneLine(artworkMessageText(message.body, message.artwork))
    if (line.isNotEmpty()) return line
    message.attachments.firstOrNull()?.let { attachment ->
        val type = attachment.type.orEmpty()
        return when {
            type.startsWith("image/") -> "Photo"
            type.startsWith("audio/") -> "Audio"
            type.startsWith("video/") -> "Video"
            else -> "File: ${attachment.name ?: "Encrypted file"}"
        }
    }
    if (message.artwork.isNotEmpty()) return "Sticker"
    return ""
}

/** The message a reply's quote points at, or null for a message that answers nothing. */
fun quoteRefOf(r: ResolvedMessage): MessageRef? = r.reply ?: r.thread

/** The state of the quote on [r], or null when [r] is not a reply. */
fun replyQuoteOf(r: ResolvedMessage, byKey: Map<String, ResolvedMessage>): ReplyQuote? {
    val ref = quoteRefOf(r) ?: return null
    val target = byKey[ref.key] ?: return ReplyQuote.Missing(ref)
    if (target.retracted) return ReplyQuote.Deleted(ref, target)
    return ReplyQuote.Ready(ref, target, quoteSnippet(target.shown))
}

/** The words a quote shows under its name. */
fun ReplyQuote.words(): String = when (this) {
    is ReplyQuote.Ready -> snippet
    is ReplyQuote.Deleted -> QUOTE_DELETED
    is ReplyQuote.Missing -> QUOTE_MISSING
}

/** What a screen reader hears for a quote that goes to the original. */
fun quoteGoLabel(who: String, mine: Boolean, words: String): String =
    "Go to ${if (mine) "your" else "$who's"} message: $words"
