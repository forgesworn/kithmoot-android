package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.ui.RoomState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The host's list of people in meeting mode, and the line it reads. */
class MeetingPeopleTest {
    private val me = "11".repeat(32)
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)
    private val carol = "cc".repeat(32)
    private fun tile(participant: String, name: String? = null) =
        ParticipantTile(participant, participant == me, 1, emptyList(), null, false, name = name)

    private val state = RoomState(
        selfParticipant = me,
        tiles = listOf(tile(me), tile(alice, "Alice"), tile(bob, "Bob"), tile(carol, "Carol")),
        meetingOn = true,
        meetingModerator = true,
        meetingSpeakers = listOf(me, alice),
        raisedHands = mapOf(carol to 300L, bob to 200L, alice to 100L),
    )

    @Test fun `raised hands come first, oldest first, and a speaker's hand asks for nothing`() {
        val people = meetingPeople(state)
        assertEquals(listOf("Bob", "Carol", "Alice"), people.map { it.name }, "the host is not on their own list")
        assertEquals(listOf(200L, 300L, null), people.map { it.handRaisedAt })
        assertEquals(listOf(false, false, true), people.map { it.speaker })
    }

    @Test fun `the host is told how many hands are up`() {
        assertEquals("Meeting mode is on. 2 hands raised.", meetingLine(state))
        assertEquals("Meeting mode is on. 1 hand raised.", meetingLine(state.copy(raisedHands = mapOf(bob to 1L))))
        assertEquals("Meeting mode is on. No hands raised.", meetingLine(state.copy(raisedHands = emptyMap())))
        assertEquals("Meeting mode: you are a speaker.", meetingLine(state.copy(meetingModerator = false)))
    }
}
