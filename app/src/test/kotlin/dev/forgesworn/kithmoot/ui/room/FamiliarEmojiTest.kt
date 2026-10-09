package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.R
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class FamiliarEmojiTest {
    @Test fun `familiar picker begins with recognisable thumbs and sends standard Unicode`() {
        val choices = emojiPickerEntries(EmojiSection.FAMILIAR, "", 3, false)
        assertEquals(listOf("👍🏽", "👎🏽"), choices.take(2).map { it.first })
        assertEquals("👍🏽", emojiForSkinTone("👍", 3))
        assertEquals("😂", emojiForSkinTone("😂", 3))
        assertTrue("👎" in REACTION_EMOJIS)
        assertTrue(choices.all { EmojiCatalog.accepts(it.first) })
        assertTrue(choices.none { it.first.startsWith(":") })
        assertEquals(FAMILIAR_ART.filter { it.category != "forgesworn" }.map { it.emoji }.distinct().size, choices.size)
        val upDrawables = listOf(R.drawable.fm_thumbs_up, R.drawable.fm_thumbs_up_tone1, R.drawable.fm_thumbs_up_tone2, R.drawable.fm_thumbs_up_tone3, R.drawable.fm_thumbs_up_tone4, R.drawable.fm_thumbs_up_tone5)
        val downDrawables = listOf(R.drawable.fm_thumbs_down, R.drawable.fm_thumbs_down_tone1, R.drawable.fm_thumbs_down_tone2, R.drawable.fm_thumbs_down_tone3, R.drawable.fm_thumbs_down_tone4, R.drawable.fm_thumbs_down_tone5)
        for (tone in HUMAN_SKIN_TONES.indices) {
            val up = withSkinTone("👍", tone); val down = withSkinTone("👎", tone)
            assertEquals("👍" + HUMAN_SKIN_TONES[tone], up)
            assertEquals("👎" + HUMAN_SKIN_TONES[tone], down)
            assertEquals(upDrawables[tone], familiarArtworkDrawable(up)); assertEquals(downDrawables[tone], familiarArtworkDrawable(down))
            assertEquals(listOf(up, down), artworkMatches("$up $down").map { it.value })
        }
    }
    @Test fun `all mapped familiar artwork has a local drawable including VS16 aliases`() {
        for (art in FAMILIAR_ART) {
            assertNotNull(familiarArtworkDrawable(art.emoji), art.slug)
            assertNotNull(familiarArtworkDrawable(art.emoji.replace("\uFE0F", "")), art.slug)
            if (art.toneable) HUMAN_SKIN_TONES.indices.forEach { tone -> assertNotNull(familiarArtworkDrawable(withSkinTone(art.emoji, tone)), art.slug) }
        }
        assertNull(familiarArtworkDrawable("not emoji"))
    }
    @Test fun `all bundled ForgeMoji images match their source-pinned byte lengths and digests`() {
        val manifest = Json.parseToJsonElement(File("src/main/assets/emoji/manifest.json").readText()).jsonObject
        assertEquals("0.1.2", manifest.getValue("source").jsonObject.getValue("version").jsonPrimitive.content)
        assertEquals(FAMILIAR_ART.size, manifest.getValue("definitions").jsonArray.size)
        val artwork = manifest.getValue("artwork").jsonArray
        assertEquals(FAMILIAR_ART.size + FAMILIAR_ART.count { it.toneable } * (HUMAN_SKIN_TONES.size - 1), artwork.size)
        for (entry in artwork) {
            val art = entry.jsonObject
            val slug = art.getValue("slug").jsonPrimitive.content
            val file = File("src/main/res/drawable-nodpi/fm_${slug.replace('-', '_')}.png")
            assertEquals(art.getValue("bytes").jsonPrimitive.long, file.length(), slug)
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(art.getValue("sha256").jsonPrimitive.content, digest, slug)
        }
    }
    @Test fun `inline artwork preserves exact received Unicode and does not split unsupported clusters`() {
        val text = "hello 👍🏿 ❤️ and :km_facepalm:"
        val matches = artworkMatches(text)
        assertEquals(listOf("👍🏿", "❤️", ":km_facepalm:"), matches.map { it.value })
        for (match in matches) assertEquals(match.value, text.substring(match.start, match.end))
        for (sequence in listOf("👍🏽\u200D🔥", "❤️\u200D🔥", "1️⃣", "#️⃣", "👨‍👩‍👧‍👦", "🇬🇧", "👍\uFE0E", "👍\u20E3", "👍\u0301")) {
            assertTrue(artworkMatches(sequence).isEmpty(), sequence)
        }
    }
    @Test fun `URLs keep their emoji and legacy shortcode text without inline images`() {
        val body = "👍 see https://example.test/👍🏽/:km_laugh:?q=❤️ then 👎"
        val tokens = splitLinks(body)
        val matches = tokens.filterIsInstance<MessageToken.Text>().flatMap { artworkMatches(it.value) }
        assertEquals(listOf("👍", "👎"), matches.map { it.value })
        assertEquals("https://example.test/👍🏽/:km_laugh:?q=❤️", tokens.filterIsInstance<MessageToken.Link>().single().url)
    }
    @Test fun `donkey has a simple public name and the ordinary sacred stone stays public`() {
        assertEquals("Donkey", familiarEmojiTitle("🫏"))
        assertEquals("🫏 Donkey", emojiPickerDescription("🫏", "crypto donkey orange mascot"))
        assertTrue(emojiPickerEntries(EmojiSection.FAMILIAR, "donkey", 0, false).any { it.first == "🫏" })
        assertEquals(listOf("🪨"), emojiPickerEntries(EmojiSection.FAMILIAR, "600", 0, false).map { it.first })
        val unlocked = emojiPickerEntries(EmojiSection.FAMILIAR, "600", 0, true)
        assertEquals(1, unlocked.count { it.first == "🪨" })
        assertEquals(8, unlocked.count { isCultEmoji(it.first) })
        assertTrue(EmojiCatalog.accepts("🪨")); assertNotNull(familiarArtworkDrawable("🪨"))
        assertEquals(listOf("🪨"), artworkMatches("🪨").map { it.value })
    }
    @Test fun `spider Bitcoin and all nine brand graphics have explicit local meanings`() {
        for (emoji in listOf("🫏", "🪨", "🕷️", "₿")) {
            assertTrue(EmojiCatalog.accepts(emoji)); assertNotNull(familiarArtworkDrawable(emoji))
            assertEquals(listOf(emoji), artworkMatches(emoji).map { it.value })
        }
        assertEquals(familiarArtworkDrawable("🕷️"), familiarArtworkDrawable("🕷"))
        val codes = listOf(":fs_forgesworn:", ":fs_kindred:", ":fs_kithmoot:", ":fs_mysignet:", ":fs_vitark:", ":fs_vitark_den:", ":fs_vitark_train:", ":fs_vitark_still:", ":fs_vitark_record:")
        assertEquals(codes.toSet(), emojiPickerEntries(EmojiSection.FORGESWORN, "", 0, false).map { it.first }.toSet())
        assertTrue(emojiPickerEntries(EmojiSection.FAMILIAR, "", 0, false).none { it.first in codes })
        for (code in codes) {
            assertTrue(EmojiCatalog.accepts(code)); assertNotNull(familiarArtworkDrawable(code))
            assertEquals(listOf(code), artworkMatches(code).map { it.value })
        }
        for (unknown in listOf(":fs_bad:", ":fs_kithmoot_bad:", "$", "£", "₿₿")) {
            assertFalse(EmojiCatalog.accepts(unknown), unknown)
        }
        assertTrue(artworkMatches(":fs_bad:").isEmpty())
        val link = splitLinks("https://example.test/:fs_kithmoot:/₿").single()
        assertTrue(link is MessageToken.Link)
    }
    @Test fun `all Unicode remains searchable while flags are hidden and member picker stays locked`() {
        assertTrue(emojiPickerEntries(EmojiSection.UNICODE, "flag", 0, false).isEmpty())
        val familiar = FAMILIAR_ART.map { it.emoji }.toSet()
        val unicode = emojiPickerEntries(EmojiSection.UNICODE, "", 0, false)
        assertTrue(unicode.size > 1_000)
        assertTrue(unicode.any { it.first == "🦄" })
        assertTrue(unicode.any { it.first !in familiar })
        assertTrue(emojiPickerEntries(EmojiSection.FAMILIAR, "600", 0, false).none { isCultEmoji(it.first) })
        assertTrue(emojiPickerEntries(EmojiSection.MEMBER, "", 0, true).all { isCultEmoji(it.first) })
        assertTrue(emojiPickerEntries(EmojiSection.CHARACTERS, "", 0, false).all { isOriginalEmoji(it.first) })
        assertTrue(emojiPickerEntries(EmojiSection.FAMILIAR, "🫶🏽", 0, false).any { it.first == "🫶🏽" })
        assertTrue(EmojiCatalog.accepts("🇬🇧"))
        assertTrue(artworkMatches(":600_facepalm:").isNotEmpty())
    }
    @Test fun `local recents discard corrupt unknown flags and locked member artwork`() {
        val stored = listOf("", "nonsense", ":fs_bad:", "🇬🇧", ":600:", "👍🏿", "👍🏿", ":fs_kithmoot:", "🪨", "🦄")
        assertEquals(listOf("👍🏿", ":fs_kithmoot:", "🪨", "🦄"), visibleRecentEmoji(stored, false))
        assertEquals(listOf(":600:", "👍🏿", ":fs_kithmoot:", "🪨", "🦄"), visibleRecentEmoji(stored, true))
        assertTrue(emojiPickerEntries(EmojiSection.MEMBER, "", 0, false).isEmpty())
        assertEquals(8, emojiPickerEntries(EmojiSection.MEMBER, "", 0, true).size)
        assertTrue(emojiPickerEntries(EmojiSection.CHARACTERS, "", 0, true).none { isCultEmoji(it.first) })
    }
}
