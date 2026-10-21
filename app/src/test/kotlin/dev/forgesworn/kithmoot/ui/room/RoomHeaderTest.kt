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
            listOf(RoomMenuItem.NOTIFICATIONS, RoomMenuItem.NEW_TASK, RoomMenuItem.PIN, RoomMenuItem.ADD_TO_PROJECT),
            listOf(RoomMenuItem.LEAVE_ROOM),
        ), room)

        // A private chat has nobody to invite and no project to join; Leave says what it leaves.
        val private = roomMenuGroups(privateChat = true, canInvite = true, narrow = false, board = true, pinned = true, canAddToProject = true)
        assertEquals(listOf(
            listOf(RoomMenuItem.ROOM_INFO),
            listOf(RoomMenuItem.NOTIFICATIONS, RoomMenuItem.NEW_TASK, RoomMenuItem.UNPIN),
            listOf(RoomMenuItem.LEAVE_PRIVATE),
        ), private)
        assertEquals("Leave private chat…", RoomMenuItem.LEAVE_PRIVATE.label)

        // Nothing to add or pin, and no board: Notifications, first in the middle group as on the web, is left.
        val bare = roomMenuGroups(privateChat = false, canInvite = false, narrow = false, board = false, pinned = null, canAddToProject = false)
        assertEquals(listOf(listOf(RoomMenuItem.ROOM_INFO), listOf(RoomMenuItem.NOTIFICATIONS), listOf(RoomMenuItem.LEAVE_ROOM)), bare)
        // Without it too, the middle group goes, divider and all.
        val none = roomMenuGroups(privateChat = false, canInvite = false, narrow = false, board = false, pinned = null, canAddToProject = false,
            notifications = false)
        assertEquals(listOf(listOf(RoomMenuItem.ROOM_INFO), listOf(RoomMenuItem.LEAVE_ROOM)), none)
    }

    @Test fun `the call banner names who is on the call, never by key`() {
        assertEquals("Rowan", namesLine(listOf("Rowan")))
        assertEquals("Rowan and Priya", namesLine(listOf("Rowan", "Priya")))
        assertEquals("Rowan, Priya and Sam", namesLine(listOf("Rowan", "Priya", "Sam")))
        assertEquals("Rowan, Priya and 2 more", namesLine(listOf("Rowan", "Priya", "Sam", "Kit")))
        val state = RoomState(selfParticipant = a, tiles = listOf(ParticipantTile(b, false, 1, emptyList(), null, false, name = "Rowan")),
            currentCall = CallSummary(b, 2, 100, listOf(b, tally)))
        assertEquals("Call on · Rowan and Somebody", callOnText(state))
        assertEquals("Call on", callOnText(state.copy(currentCall = null)))
    }

    @Test fun `two people with the same name get their short key, and nobody else does`() {
        val c = "dd".repeat(32)
        fun tile(id: String, name: String?, self: Boolean = false) = ParticipantTile(id, self, 1, emptyList(), null, false, name = name)
        val state = RoomState(selfParticipant = a, tiles = listOf(
            tile(a, "Ada", self = true), tile(b, "Robin"), tile(c, "robin"), tile(tally, null),
        ))
        assertEquals("Robin (${shortNpub(b)})", callPersonName(state, b))
        assertEquals("robin (${shortNpub(c)})", callPersonName(state, c))
        // Compared trimmed and ignoring case, as the web does; shown as given.
        assertEquals(setOf(b, c), sameNamed(listOf(tile(b, "Robin"), tile(c, " ROBIN ")), emptyMap()))
        // A nameless person is Somebody, never a key; you are never keyed.
        assertEquals("Somebody", callPersonName(state, tally))
        assertEquals("Ada", callPersonName(state, a))
        // Somebody else claiming your name gets the key; you still do not.
        val clash = state.copy(tiles = listOf(tile(a, "Ada", self = true), tile(c, "ADA")))
        assertEquals("ADA (${shortNpub(c)})", callPersonName(clash, c))
        assertEquals("Ada", callPersonName(clash, a))
        assertEquals("Robin", callPersonName(state.copy(tiles = listOf(tile(b, "Robin"))), b))
    }

    @Test fun `the agents prompt names one agent and counts several`() {
        assertEquals("Tally is an agent on this call.", agentsConsentLine(listOf("Tally"), 1))
        assertEquals("2 agents are on this call.", agentsConsentLine(listOf("Tally", "Scribe"), 2))
        assertEquals("2 agents are on this call.", agentsConsentLine(listOf("Tally"), 2))
    }

    @Test fun `a call's length reads as minutes and seconds, then hours`() {
        assertEquals("0:00", callElapsed(100, 90))
        assertEquals("4:07", callElapsed(0, 247))
        assertEquals("12:03", callElapsed(0, 723))
        assertEquals("1:02:09", callElapsed(0, 3729))
    }
}
