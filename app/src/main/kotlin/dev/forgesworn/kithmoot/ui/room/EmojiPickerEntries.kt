package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.*

internal enum class EmojiSection(val title: String) { FAMILIAR("Familiar"), FORGESWORN("ForgeSworn"), CHARACTERS("Characters"), MEMBER("600"), UNICODE("More emoji") }
internal val SKIN_TONE_NAMES = listOf("Classic yellow", "Light skin", "Medium-light skin", "Medium skin", "Medium-dark skin", "Dark skin")

/** Local search preserves all Unicode choices while putting our familiar artwork first. */
internal fun emojiPickerEntries(section: EmojiSection, query: String, tone: Int, memberPack: Boolean): List<Pair<String, String>> {
    fun entries(artwork: List<FamiliarArt>) = artwork.sortedBy {
        when (it.emoji.replace("\uFE0F", "")) { "👍" -> -2; "👎" -> -1; else -> 0 }
    }.map { (if (it.toneable) withSkinTone(it.emoji, tone) else it.emoji) to it.keywords }
    val familiar = entries(FAMILIAR_ART.filter { it.category != "forgesworn" })
    val brands = entries(FAMILIAR_ART.filter { it.category == "forgesworn" })
    val allFamiliar = familiar + brands
    val characters = ORIGINAL_EMOJIS
    val memberChoices = if (memberPack) CULT_EMOJIS else emptyList()
    val standard = if (section == EmojiSection.UNICODE || query.isNotBlank()) EmojiCatalog.entries.filterNot { it.second.contains("flag", true) } else emptyList()
    val choices = if (query.isNotBlank()) allFamiliar + characters + memberChoices + standard else when (section) {
        EmojiSection.FAMILIAR -> familiar
        EmojiSection.FORGESWORN -> brands
        EmojiSection.CHARACTERS -> characters
        EmojiSection.MEMBER -> memberChoices
        EmojiSection.UNICODE -> standard
    }
    return choices.asSequence().distinctBy { it.first }.filter { (emoji, words) -> "$emoji $words".contains(query.trim(), true) }
        .take(if (query.isBlank()) choices.size else 120).toList()
}

internal fun emojiForSkinTone(emoji: String, tone: Int): String =
    if (FAMILIAR_ART.any { it.emoji == emoji && it.toneable }) withSkinTone(emoji, tone) else emoji

internal fun emojiPickerDescription(emoji: String, keywords: String): String =
    "$emoji ${familiarEmojiTitle(emoji) ?: keywords}"
