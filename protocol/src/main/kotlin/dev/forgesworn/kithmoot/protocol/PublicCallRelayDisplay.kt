package dev.forgesworn.kithmoot.protocol

/**
 * How an operator's own words are shown: they may be hidden, reordered or
 * spoofed by characters that draw nothing. The same rules as the web app.
 */
object PublicCallRelayDisplay {
    /** Bidi controls, zero-width characters, the BOM and line or paragraph
     *  separators, which could reorder or hide what an operator's note says. */
    private val HIDDEN = Regex("[؜ᅟᅠ᠎​-‏ -‮⁠-⁯ㅤ﻿ﾠ￹-￻]")
    private val CONTROL = Regex("\\p{Cc}")

    /** The note (or any operator text) with hidden formatting and control
     *  characters removed and the ends trimmed. Show it as the operator's
     *  claim, in at most two lines. */
    fun cleanOperatorNote(note: String): String = note.replace(HIDDEN, "").replace(CONTROL, "").trim()

    /** A transport key short enough to compare by eye: the first eight hex
     *  digits, an ellipsis, the last four. */
    fun shortTransportKey(key: String): String =
        if (key.length <= 12) key else "${key.take(8)}…${key.takeLast(4)}"
}
