package dev.forgesworn.kithmoot.ui.room

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Splitting message text into plain runs and http(s) links, as the web and
 * desktop apps do (kithmoot `app/src/linkify.ts`, whose cases the tests
 * mirror). A pasted URL was plain text on Android while it was a link on
 * desktop. Deliberately narrow: http and https only, so a `javascript:`,
 * `data:` or `intent:` string never becomes a working link, whatever
 * somebody pastes.
 */
sealed class MessageToken {
    data class Text(val value: String) : MessageToken()
    data class Link(val url: String) : MessageToken()
}

// (?U): `\s` covers Unicode spaces too, as JavaScript's does.
private val URL_PATTERN = Regex("(?U)https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
private val TRAILING = setOf('.', ',', '!', '?', ';', ':', '\'', '"', ')', ']', '}')
private val OPEN_FOR_CLOSE = mapOf(')' to '(', ']' to '[', '}' to '{')

/** A Wikipedia-style `.../Foo_(bar)` keeps its closing bracket; a sentence's own bracket around a bare link does not. */
private fun bracketsBalance(s: CharSequence, open: Char, close: Char): Boolean {
    var depth = 0
    for (ch in s) { if (ch == open) depth++ else if (ch == close) depth-- }
    return depth >= 0
}

/**
 * Split [text] into plain runs and http(s) URLs. Trailing punctuation that
 * belongs to the sentence (the full stop after "see https://example.com.") is
 * left as text; a closing bracket matched inside the URL stays in it.
 */
fun splitLinks(text: String): List<MessageToken> {
    val tokens = mutableListOf<MessageToken>()
    var at = 0
    for (match in URL_PATTERN.findAll(text)) {
        val start = match.range.first
        var end = match.range.last + 1
        while (end > start) {
            val last = text[end - 1]
            if (last !in TRAILING) break
            val open = OPEN_FOR_CLOSE[last]
            if (open != null && bracketsBalance(text.subSequence(start, end), open, last)) break
            end--
        }
        if (end <= start) continue
        if (start > at) tokens += MessageToken.Text(text.substring(at, start))
        tokens += MessageToken.Link(text.substring(start, end))
        at = end
    }
    if (at < text.length) tokens += MessageToken.Text(text.substring(at))
    return tokens
}

/**
 * [text] with each http(s) URL a tappable link that opens in the browser. Its
 * visible text is the URL itself, so nothing is hidden behind other wording.
 */
fun linkedMessage(text: String, linkColour: Color): AnnotatedString = buildAnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColour, textDecoration = TextDecoration.Underline))
    for (token in splitLinks(text)) when (token) {
        is MessageToken.Text -> append(token.value)
        is MessageToken.Link -> withLink(LinkAnnotation.Url(token.url, styles)) { append(token.url) }
    }
}
