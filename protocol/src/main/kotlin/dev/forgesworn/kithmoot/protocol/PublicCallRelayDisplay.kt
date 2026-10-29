package dev.forgesworn.kithmoot.protocol

/**
 * How an operator's own words are shown: they may be hidden, reordered or
 * spoofed by characters that draw nothing. The same rules as the web app.
 */
/** An operator's privacy-notice link as the picker shows it: the link and its hostname. */
data class PublicRelayPrivacyLink(val url: String, val host: String)

object PublicCallRelayDisplay {
    /** Bidi controls, zero-width characters, the BOM and line or paragraph
     *  separators, which could reorder or hide what an operator's note says. */
    private val HIDDEN = Regex("[\\u061c\\u115f\\u1160\\u180e\\u200b-\\u200f\\u2028-\\u202e\\u2060-\\u206f\\u3164\\ufeff\\uffa0\\ufff9-\\ufffb]")
    private val CONTROL = Regex("\\p{Cc}")

    /** The note (or any operator text) with hidden formatting and control
     *  characters removed and the ends trimmed. Show it as the operator's
     *  claim, in at most two lines. */
    fun cleanOperatorNote(note: String): String = note.replace(HIDDEN, "").replace(CONTROL, "").trim()

    /** A transport key short enough to compare by eye: the first eight hex
     *  digits, an ellipsis, the last four. */
    fun shortTransportKey(key: String): String =
        if (key.length <= 12) key else "${key.take(8)}\u2026${key.takeLast(4)}"

    /**
     * A verified advert's privacy link with its hostname, or null when the
     * host fails [PublicCallRelayAdvert.hostAllowed], the rules a relay's own
     * host must meet before any contact: the advert is still listed. Never
     * fetched, prefetched or stored (client rule 8 of spry-turn
     * `docs/public-mode.md`): it opens only when the person taps it.
     */
    fun privacyLink(url: String?): PublicRelayPrivacyLink? {
        if (url == null || !PublicCallRelayAdvert.privacyValid(url)) return null
        val host = url.substring("https://".length, url.indexOf('/', "https://".length))
        return if (PublicCallRelayAdvert.hostAllowed(host)) PublicRelayPrivacyLink(url, host) else null
    }
}
