package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.CallSummary
import dev.forgesworn.kithmoot.ui.RoomState
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomHeaderTest {
    private val a = "aa".repeat(32)
    private val b = "bb".repeat(32)
    private val tally = "cc".repeat(32)

    @Test fun `the line under the name counts who is here, people and agents apart`() {
        assertEquals("", presenceLine(emptyList(), emptySet()))
        assertEquals("1 here", presenceLine(listOf(a), emptySet()))
        assertEquals("2 here", presenceLine(listOf(a, b), emptySet()))
        assertEquals("2 people, 1 agent", presenceLine(listOf(a, b, tally), setOf(tally)))
        assertEquals("1 person, 2 agents", presenceLine(listOf(a, b, tally), setOf(b, tally)))
    }

    @Test fun `the menu offers only what applies, in the web's groups`() {
        val room = roomMenuGroups(privateChat = false, canInvite = true, narrow = true, board = true, pinned = false, canAddToProject = true)
        assertEquals(listOf(
            listOf(RoomMenuItem.ROOM_INFO, RoomMenuItem.SEARCH, RoomMenuItem.INVITE),
            listOf(RoomMenuItem.NEW_TASK, RoomMenuItem.PIN, RoomMenuItem.ADD_TO_PROJECT),
            listOf(RoomMenuItem.LEAVE_ROOM),
        ), room)

        // A private chat has nobody to invite and no project to join; Leave says what it leaves.
        val private = roomMenuGroups(privateChat = true, canInvite = true, narrow = false, board = true, pinned = true, canAddToProject = true)
        assertEquals(listOf(
            listOf(RoomMenuItem.ROOM_INFO),
            listOf(RoomMenuItem.NEW_TASK, RoomMenuItem.UNPIN),
            listOf(RoomMenuItem.LEAVE_PRIVATE),
        ), private)
        assertEquals("Leave private chat…", RoomMenuItem.LEAVE_PRIVATE.label)

        // Nothing to add or pin, and no board: the middle group goes, divider and all.
        val bare = roomMenuGroups(privateChat = false, canInvite = false, narrow = false, board = false, pinned = null, canAddToProject = false)
        assertEquals(listOf(listOf(RoomMenuItem.ROOM_INFO), listOf(RoomMenuItem.LEAVE_ROOM)), bare)
    }

    @Test fun `the call banner names who started it and counts who is on it`() {
        assertEquals("Rowan started a call · 3 on it", callBannerText("Rowan", 3))
        assertEquals("Rowan started a call", callBannerText("Rowan", 1))
        val state = RoomState(tiles = listOf(ParticipantTile(b, false, 1, emptyList(), null, false, name = "Rowan")),
            currentCall = CallSummary(b, 2, 100))
        assertEquals("Rowan", callStarterName(state))
        assertEquals(shortNpub(a), callStarterName(state.copy(currentCall = CallSummary(a, 1, 100))))
        assertEquals("Somebody", callStarterName(state.copy(currentCall = null)))
    }

    @Test fun `a call's length reads as minutes and seconds, then hours`() {
        assertEquals("0:00", callElapsed(100, 90))
        assertEquals("4:07", callElapsed(0, 247))
        assertEquals("12:03", callElapsed(0, 723))
        assertEquals("1:02:09", callElapsed(0, 3729))
    }
}
