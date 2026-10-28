package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicCallRelayDisplayTest {
    private fun clean(text: String) = PublicCallRelayDisplay.cleanOperatorNote(text)

    @Test fun stripsBidiZeroWidthBomAndLineSeparators() {
        // The web test: a BOM and an isolate pair around plain letters.
        assertEquals("abc", clean("\ufeffa\u2066b\u2069c"))
        // Every hidden range, one character each.
        for (hidden in listOf(
            "\u061c", "\u115f", "\u1160", "\u180e", "\u200b", "\u200c", "\u200d", "\u200e", "\u200f", "\u2028", "\u2029",
            "\u202a", "\u202b", "\u202c", "\u202d", "\u202e", "\u2060", "\u2061", "\u2066", "\u2067", "\u2068", "\u2069", "\u206f",
            "\u3164", "\ufeff", "\uffa0", "\ufff9", "\ufffa", "\ufffb",
        )) assertEquals("a${hidden.codePointAt(0).toString(16)}", "ab", clean("a${hidden}b"))
    }

    @Test fun stripsControlCharactersAndTrims() {
        assertEquals("line break", clean("line \nbreak"))
        assertEquals("ab", clean("a\u0000b\u0007"))
        assertEquals("ab", clean("a\u0085b"))
        assertEquals("fast", clean("  fast \u00a0"))
        assertEquals("", clean(" \u200b\ufeff "))
    }

    @Test fun leavesOrdinaryTextAlone() {
        assertEquals("Run by the village hall. Fast & free.", clean("Run by the village hall. Fast & free."))
        assertEquals("Caf\u00e9 \u2014 relay \u65e5\u672c\u8a9e \ud83d\ude00", clean("Caf\u00e9 \u2014 relay \u65e5\u672c\u8a9e \ud83d\ude00"))
        // Neighbours of the stripped ranges survive: NNBSP is a space, U+2070 is a superscript zero.
        assertEquals("a\u202fb", clean("a\u202fb"))
        assertEquals("a\u2070b", clean("a\u2070b"))
        assertEquals("a\u1161b", clean("a\u1161b"))
    }

    @Test fun shortKeyIsEightDigitsAnEllipsisAndFour() {
        val key = "0123456789abcdef".repeat(4)
        assertEquals("01234567\u2026cdef", PublicCallRelayDisplay.shortTransportKey(key))
        assertEquals("abc", PublicCallRelayDisplay.shortTransportKey("abc"))
    }

    @Test fun privacyLinkShowsItsHostOnlyWhereARelayHostWouldBeAllowed() {
        assertEquals(PublicRelayPrivacyLink("https://notice.example.org/legal/privacy", "notice.example.org"),
            PublicCallRelayDisplay.privacyLink("https://notice.example.org/legal/privacy"))
        assertEquals(null, PublicCallRelayDisplay.privacyLink(null))
        assertEquals(null, PublicCallRelayDisplay.privacyLink("http://notice.example.org/privacy"))
        // Valid in an advert, but a name no app should send anyone to.
        for (link in listOf("https://notice.example/privacy", "https://router.lan/privacy", "https://box.home/p", "https://a.onion/p"))
            assertEquals(link, null, PublicCallRelayDisplay.privacyLink(link))
    }
}
