package dev.forgesworn.kithmoot.ui.room

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import dev.forgesworn.kithmoot.ui.room.MessageToken.Link
import dev.forgesworn.kithmoot.ui.room.MessageToken.Text
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same cases as the web and desktop apps' `linkify.test.ts`, so a message links alike everywhere. */
class MessageLinksTest {
    @Test fun `finds nothing to split in plain text`() {
        assertEquals(listOf(Text("just a message, no links here")), splitLinks("just a message, no links here"))
    }

    @Test fun `turns an http(s) URL into a link token, keeping the surrounding text`() {
        assertEquals(listOf(Text("see "), Link("https://example.com/docs"), Text(" for more")), splitLinks("see https://example.com/docs for more"))
        assertEquals(listOf(Link("http://example.com"), Text(" works too")), splitLinks("http://example.com works too"))
    }

    @Test fun `trims a trailing full stop off the URL and keeps it as text`() {
        assertEquals(listOf(Text("read "), Link("https://example.com/docs"), Text(".")), splitLinks("read https://example.com/docs."))
    }

    @Test fun `trims other trailing sentence punctuation the same way`() {
        for ((input, url, rest) in listOf(
            Triple("is it https://example.com?", "https://example.com", "?"),
            Triple("go to https://example.com!", "https://example.com", "!"),
            Triple("see https://example.com, then this", "https://example.com", ", then this"),
            Triple("see https://example.com; then this", "https://example.com", "; then this"),
            Triple("\"https://example.com\"", "https://example.com", "\""),
        )) {
            val tokens = splitLinks(input)
            assertEquals(url, tokens.filterIsInstance<Link>().single().url, input)
            assertEquals(Text(rest), tokens.last(), input)
        }
    }

    @Test fun `keeps a closing bracket that is part of the URL, from a balanced opening one`() {
        assertEquals(
            listOf(Text("see "), Link("https://example.com/wiki/Foo_(bar)"), Text(" for detail")),
            splitLinks("see https://example.com/wiki/Foo_(bar) for detail"),
        )
    }

    @Test fun `strips an unbalanced closing bracket that belongs to the sentence around a bare link`() {
        assertEquals(listOf(Text("(see "), Link("https://example.com"), Text(")")), splitLinks("(see https://example.com)"))
    }

    @Test fun `never matches a javascript, data, mailto or intent scheme`() {
        for (text in listOf(
            "javascript:alert(1)", "data:text/html,<script>alert(1)</script>", "not a link: mailto:a@b.com",
            "intent://scan/#Intent;scheme=zxing;end",
        )) assertEquals(listOf(Text(text)), splitLinks(text))
    }

    @Test fun `finds more than one link in the same message`() {
        assertEquals(
            listOf(Link("https://a.example"), Text(" and "), Link("https://b.example"), Text(" too")),
            splitLinks("https://a.example and https://b.example too"),
        )
    }

    @Test fun `does not truncate a very long URL`() {
        val long = "https://example.com/" + "a".repeat(500)
        assertEquals(listOf(Text("see "), Link(long)), splitLinks("see $long"))
    }

    @Test fun `a Unicode space or a tab ends a URL, as in JavaScript`() {
        for (space in listOf("\u00a0", "\u3000", "\u2028", "\t")) {
            assertEquals(listOf(Link("https://example.com"), Text(space + "next")), splitLinks("https://example.com" + space + "next"))
        }
    }

    @Test fun `the rendered message keeps its exact text and links only the URLs`() {
        val text = "read https://example.com/docs. then javascript:alert(1)"
        val rendered = linkedMessage(text, Color.Blue)
        assertEquals(text, rendered.text)
        val links = rendered.getLinkAnnotations(0, rendered.length)
        assertEquals(listOf("https://example.com/docs"), links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals("https://example.com/docs", rendered.text.substring(links.single().start, links.single().end))
    }
}
