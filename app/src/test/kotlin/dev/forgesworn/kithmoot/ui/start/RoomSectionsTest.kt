package dev.forgesworn.kithmoot.ui.start

import kotlin.test.*

class RoomSectionsTest {
    private val now = 1_800_000_000L
    private val day = 24 * 60 * 60L

    private fun room(
        id: String, openedAt: Long = now, pinned: Boolean = false, ended: Boolean = false, endsAt: Long? = null, label: String = id,
    ) = HomeRoom(id, label, RoomSource.PHONE, openedAt, null, null, false, false, ended, false, endsAt, pinned)

    private fun activity(at: Long) = RoomActivity(at, null, true, 0, 0, emptyList())

    private fun unread(count: Int) = RoomActivity(null, null, true, count, 0, emptyList())

    private fun sectionsOf(rooms: List<HomeRoom>, unreadIds: Set<String> = emptySet()) =
        rooms.associate { it.id to sectionOf(it, if (it.id in unreadIds) unread(1) else activity(it.openedAt), now) }

    /** Nine rooms, so the list is past the small-list rule. */
    private fun big(vararg extra: HomeRoom) = extra.toList() + (1..9 - extra.size).map { room("filler$it") }

    @Test fun `a recent read room is Recent`() = assertEquals(HomeSection.RECENT, sectionOf(room("a", now), activity(now - day), now))

    @Test fun `seven days exactly is still Recent and one second more is Older`() {
        assertEquals(HomeSection.RECENT, sectionOf(room("a", now), activity(now - 7 * day), now))
        assertEquals(HomeSection.OLDER, sectionOf(room("a", now), activity(now - 7 * day - 1), now))
    }

    @Test fun `reading an old conversation does not move it to Recent`() {
        val old = activity(now - 30 * day)
        assertEquals(HomeSection.OLDER, sectionOf(room("a", openedAt = now), old, now))
    }

    @Test fun `rooms without readable message times remain outside the default folds`() {
        assertEquals(HomeSection.OTHER, sectionOf(room("a", openedAt = now), null, now))
        assertEquals(HomeSection.OTHER, sectionOf(room("a", openedAt = now - 30 * day), activity(0), now))
        assertFalse(HomeSection.OTHER.foldable)
    }

    @Test fun `unread from a person is Unread, agents do not count`() {
        assertEquals(HomeSection.UNREAD, sectionOf(room("a"), unread(2), now))
        assertEquals(HomeSection.RECENT, sectionOf(room("a"), RoomActivity(now, null, true, 0, 5, emptyList()), now))
    }

    @Test fun `pinned beats unread and ended`() {
        assertEquals(HomeSection.PINNED, sectionOf(room("a", pinned = true, ended = true), unread(1), now))
    }

    @Test fun `ended beats unread, including a conference past its end`() {
        assertEquals(HomeSection.ENDED, sectionOf(room("a", ended = true), unread(1), now))
        assertEquals(HomeSection.ENDED, sectionOf(room("a", endsAt = now - 1), unread(1), now))
        assertEquals(HomeSection.RECENT, sectionOf(room("a", endsAt = now + 60), activity(now), now))
    }

    @Test fun `a room is in exactly one section and empty sections are dropped`() {
        val rooms = big(room("p", pinned = true), room("o", now - 30 * day), room("e", ended = true))
        val groups = groupRooms(rooms, "", sectionsOf(rooms))
        assertEquals(listOf(HomeSection.PINNED, HomeSection.RECENT, HomeSection.OLDER, HomeSection.ENDED), groups.map { it.section })
        assertEquals(rooms.map { it.id }.sorted(), groups.flatMap { it.rooms }.map { it.id }.sorted())
        assertEquals(listOf("o"), groups.first { it.section == HomeSection.OLDER }.rooms.map { it.id })
    }

    @Test fun `eight rooms or fewer show no headings and pinned sort first`() {
        val rooms = (1..7).map { room("r$it") } + room("pin", pinned = true, openedAt = 1)
        val groups = groupRooms(rooms, "", sectionsOf(rooms))
        assertEquals(1, groups.size)
        assertNull(groups.single().section)
        assertEquals("pin", groups.single().rooms.first().id)
        assertEquals(listOf("r1", "r2", "r3"), groups.single().rooms.drop(1).take(3).map { it.id })
    }

    @Test fun `nine rooms are sectioned`() {
        val rooms = big()
        assertTrue(groupRooms(rooms, "", sectionsOf(rooms)).all { it.section != null })
    }

    @Test fun `a search is one flat list that ignores folds, pinned first`() {
        val rooms = big(room("alpha-old", now - 30 * day), room("alpha-pin", now - 40 * day, pinned = true), room("alpha-end", ended = true))
        val groups = groupRooms(rooms, "ALPHA", sectionsOf(rooms))
        assertEquals(1, groups.size)
        assertNull(groups.single().section)
        assertEquals(listOf("alpha-pin", "alpha-old", "alpha-end"), groups.single().rooms.map { it.id })
    }

    @Test fun `order within a section is the order given`() {
        val rooms = big(room("x"), room("y"))
        val groups = groupRooms(rooms, "", sectionsOf(rooms))
        assertEquals(rooms.map { it.id }, groups.single { it.section == HomeSection.RECENT }.rooms.map { it.id })
    }

    @Test fun `held sections keep the room where it was until the hold ends`() {
        val before = mapOf("a" to HomeSection.UNREAD, "b" to HomeSection.RECENT)
        val after = mapOf("a" to HomeSection.RECENT, "b" to HomeSection.RECENT, "c" to HomeSection.OLDER)
        assertEquals(mapOf("a" to HomeSection.UNREAD, "b" to HomeSection.RECENT, "c" to HomeSection.OLDER), holdSections(before, after, held = true))
        assertEquals(after, holdSections(before, after, held = false))
    }

    @Test fun `headings show the count only while folded`() {
        assertEquals("Older · 14", sectionHeading(HomeSection.OLDER, 14, folded = true))
        assertEquals("Older", sectionHeading(HomeSection.OLDER, 14, folded = false))
        assertEquals(listOf("Pinned", "Unread", "Recent", "Older", "Other rooms", "Ended"), HomeSection.entries.map { it.label })
        assertEquals(listOf(false, false, false, true, false, true), HomeSection.entries.map { it.foldable })
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
