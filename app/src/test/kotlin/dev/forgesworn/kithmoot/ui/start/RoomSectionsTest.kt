package dev.forgesworn.kithmoot.ui.start

import kotlin.test.*

class RoomSectionsTest {
    private val now = 1_800_000_000L
    private val day = 24 * 60 * 60L

    private fun room(
        id: String, openedAt: Long = now, pinned: Boolean = false, ended: Boolean = false, endsAt: Long? = null, label: String = id,
    ) = HomeRoom(id, label, RoomSource.PHONE, openedAt, null, null, false, false, ended, false, endsAt, pinned)

    private fun sectionsOf(rooms: List<HomeRoom>) = rooms.associate { it.id to sectionOf(it, now) }

    @Test fun `a live room is a chat, whatever its age`() {
        assertEquals(HomeSection.CHATS, sectionOf(room("a", now), now))
        assertEquals(HomeSection.CHATS, sectionOf(room("a", now - 30 * day), now))
        assertEquals(HomeSection.CHATS, sectionOf(room("a", endsAt = now + 60), now))
    }

    @Test fun `pinned beats ended`() = assertEquals(HomeSection.PINNED, sectionOf(room("a", pinned = true, ended = true), now))

    @Test fun `ended includes a conference past its end`() {
        assertEquals(HomeSection.ENDED, sectionOf(room("a", ended = true), now))
        assertEquals(HomeSection.ENDED, sectionOf(room("a", endsAt = now - 1), now))
    }

    @Test fun `pinned then chats then ended, each room once, empty sections dropped`() {
        val rooms = listOf(room("p", pinned = true), room("o", now - 30 * day), room("e", ended = true), room("r"))
        val groups = groupRooms(rooms, "", sectionsOf(rooms))
        assertEquals(listOf(HomeSection.PINNED, HomeSection.CHATS, HomeSection.ENDED), groups.map { it.section })
        assertEquals(rooms.map { it.id }.sorted(), groups.flatMap { it.rooms }.map { it.id }.sorted())
        assertEquals(listOf("o", "r"), groups.first { it.section == HomeSection.CHATS }.rooms.map { it.id })
    }

    @Test fun `with nothing pinned or ended there is no heading`() {
        val rooms = (1..12).map { room("r$it") }
        val groups = groupRooms(rooms, "", sectionsOf(rooms))
        assertNull(groups.single().section)
        assertEquals(rooms.map { it.id }, groups.single().rooms.map { it.id })
    }

    @Test fun `a search is one flat list that ignores folds, pinned first`() {
        val rooms = listOf(room("alpha-old", now - 30 * day), room("alpha-pin", now - 40 * day, pinned = true), room("alpha-end", ended = true), room("beta"))
        val groups = groupRooms(rooms, "ALPHA", sectionsOf(rooms))
        assertNull(groups.single().section)
        assertEquals(listOf("alpha-pin", "alpha-old", "alpha-end"), groups.single().rooms.map { it.id })
    }

    @Test fun `held sections keep the room where it was until the hold ends`() {
        val before = mapOf("a" to HomeSection.PINNED, "b" to HomeSection.CHATS)
        val after = mapOf("a" to HomeSection.CHATS, "b" to HomeSection.CHATS, "c" to HomeSection.ENDED)
        assertEquals(mapOf("a" to HomeSection.PINNED, "b" to HomeSection.CHATS, "c" to HomeSection.ENDED), holdSections(before, after, held = true))
        assertEquals(after, holdSections(before, after, held = false))
    }

    @Test fun `headings show the count only while folded`() {
        assertEquals("Ended · 14", sectionHeading(HomeSection.ENDED, 14, folded = true))
        assertEquals("Ended", sectionHeading(HomeSection.ENDED, 14, folded = false))
        assertEquals(listOf("Pinned", "Chats", "Ended"), HomeSection.entries.map { it.label })
        assertEquals(listOf(false, false, true), HomeSection.entries.map { it.foldable })
    }

    @Test fun `chips filter by unread, mentions and project, and a lost project reads as All`() {
        val a = room("a"); val b = room("b"); val c = room("c").copy(project = "Garden")
        val unread = mapOf("a" to 2); val mentioned = setOf("b")
        assertEquals(listOf("a"), listOf(a, b, c).filter { matchesFilter(it, ChatFilter.Unread, unread, mentioned) }.map { it.id })
        assertEquals(listOf("b"), listOf(a, b, c).filter { matchesFilter(it, ChatFilter.Mentions, unread, mentioned) }.map { it.id })
        assertEquals(listOf("c"), listOf(a, b, c).filter { matchesFilter(it, ChatFilter.Project("Garden"), unread, mentioned) }.map { it.id })
        for (filter in listOf(ChatFilter.All, ChatFilter.Unread, ChatFilter.Mentions, ChatFilter.Project("Garden")))
            assertEquals(filter, chatFilterOf(chatFilterKey(filter), listOf("Garden")))
        assertEquals(ChatFilter.All, chatFilterOf("Gone", listOf("Garden")))
        assertEquals(ChatFilter.All, chatFilterOf("\u0000none", listOf("Garden")))
    }

    @Test fun `avatar colour follows the first byte of the id modulo eight`() {
        assertEquals(0xFF0B6B8AL, avatarColour("00" + "0".repeat(62), dark = false))
        assertEquals(0xFF0E7FA3L, avatarColour("08" + "0".repeat(62), dark = true))
        assertEquals(0xFF7A4A12L, avatarColour("ff" + "0".repeat(62), dark = false))
    }

    @Test fun `avatar letter is the first letter or digit, else hash`() {
        assertEquals("G", avatarLetter("garden"))
        assertEquals("7", avatarLetter("  7 days"))
        assertEquals("E", avatarLetter("😀 eggs"))
        assertEquals("#", avatarLetter("😀 ..."))
    }
}
