package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicCallRelayDisplayTest {
    private fun clean(text: String) = PublicCallRelayDisplay.cleanOperatorNote(text)

    @Test fun stripsBidiZeroWidthBomAndLineSeparators() {
        // The web test: a BOM and an isolate pair around plain letters.
        assertEquals("abc", clean("﻿a⁦b⁩c"))
        // Every hidden range, one character each.
        for (hidden in listOf(
            "؜", "ᅟ", "ᅠ", "᠎", "​", "‌", "‍", "‎", "‏", " ", " ",
            "‪", "‫", "‬", "‭", "‮", "⁠", "⁡", "⁦", "⁧", "⁨", "⁩", "⁯",
            "ㅤ", "﻿", "ﾠ", "￹", "￺", "￻",
        )) assertEquals("a${hidden.codePointAt(0).toString(16)}", "ab", clean("a${hidden}b"))
    }

    @Test fun stripsControlCharactersAndTrims() {
        assertEquals("line break", clean("line \nbreak"))
        assertEquals("ab", clean("a\u0000b\u0007"))
        assertEquals("ab", clean("a\u0085b"))
        assertEquals("fast", clean("  fast  "))
        assertEquals("", clean(" ​﻿ "))
    }

    @Test fun leavesOrdinaryTextAlone() {
        assertEquals("Run by the village hall. Fast & free.", clean("Run by the village hall. Fast & free."))
        assertEquals("Café — relay 日本語 😀", clean("Café — relay 日本語 😀"))
        // Neighbours of the stripped ranges survive: NNBSP is a space, U+2070 is a superscript zero.
        assertEquals("a b", clean("a b"))
        assertEquals("a⁰b", clean("a⁰b"))
        assertEquals("aᅡb", clean("aᅡb"))
    }

    @Test fun shortKeyIsEightDigitsAnEllipsisAndFour() {
        val key = "0123456789abcdef".repeat(4)
        assertEquals("01234567…cdef", PublicCallRelayDisplay.shortTransportKey(key))
        assertEquals("abc", PublicCallRelayDisplay.shortTransportKey("abc"))
    }
}
