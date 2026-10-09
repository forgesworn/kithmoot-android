package dev.forgesworn.kithmoot.session

/** A recognised complete emoji or legacy shortcode within plain message text. */
data class ArtworkMatch(val start: Int, val end: Int, val value: String)

private val familiarUnicode: Set<String> by lazy {
    FAMILIAR_ART.flatMap { art ->
        val variants = if (art.toneable) HUMAN_SKIN_TONES.indices.map { withSkinTone(art.emoji, it) } else listOf(art.emoji)
        variants.flatMap { listOf(it, it.replace("\uFE0F", "")) }
    }.toSet()
}
private val artworkShortcodes by lazy { ((CULT_EMOJIS + ORIGINAL_EMOJIS).map { it.first } + FAMILIAR_ART.filter { it.emoji.startsWith(":") }.map { it.emoji }).toSet() }

/**
 * Only replace a whole Unicode sequence. In particular, a recognised hand in an
 * unsupported ZWJ sequence must not lose its partner, variation selector, tone
 * or enclosing keycap. Call on plain runs after splitting links.
 */
fun artworkMatches(text: String): List<ArtworkMatch> {
    val matches = mutableListOf<ArtworkMatch>()
    var at = 0
    while (at < text.length) {
        if (text[at] == ':') {
            val end = text.indexOf(':', at + 1)
            if (end > at && text.substring(at, end + 1) in artworkShortcodes) {
                matches += ArtworkMatch(at, end + 1, text.substring(at, end + 1)); at = end + 1; continue
            }
        }
        val end = emojiSequenceEnd(text, at)
        val sequence = text.substring(at, end)
        if (sequence in familiarUnicode) matches += ArtworkMatch(at, end, sequence)
        at = end
    }
    return matches
}

private fun emojiSequenceEnd(text: String, start: Int): Int {
    val first = text.codePointAt(start)
    var end = start + Character.charCount(first)
    if (first in 0x1F1E6..0x1F1FF && end < text.length && text.codePointAt(end) in 0x1F1E6..0x1F1FF) {
        end += Character.charCount(text.codePointAt(end))
    }
    while (end < text.length) {
        val cp = text.codePointAt(end)
        val type = Character.getType(cp)
        if (cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F ||
            type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) {
            end += Character.charCount(cp)
        } else if (cp == 0x200D) {
            end += Character.charCount(cp)
            if (end < text.length) end += Character.charCount(text.codePointAt(end))
        } else break
    }
    return end
}

/** Friendly artwork names are separate from the broader search keywords. */
fun familiarEmojiTitle(emoji: String): String? {
    val base = buildString {
        emoji.codePoints().forEach { cp -> if (cp != 0xFE0F && cp !in 0x1F3FB..0x1F3FF) appendCodePoint(cp) }
    }
    return FAMILIAR_ART.firstOrNull { it.emoji.replace("\uFE0F", "") == base }?.title
}
