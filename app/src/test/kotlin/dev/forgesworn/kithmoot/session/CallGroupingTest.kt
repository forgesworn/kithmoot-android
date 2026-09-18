package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One answer per person about which call they are on, folded from however
 * many devices they have in the room.
 *
 * The case that needs an answer is somebody moving a call from their laptop
 * to their phone: for a heartbeat or two both devices are in the roster and
 * they disagree, and a reader that believed both would show one person on two
 * calls at once.
 */
class CallGroupingTest {

    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))
    private val CALL = "c0ffeec0ffeec0ffeec0ffeec0ffeec0"
    private val OTHER = "1234567890abcdef1234567890abcdef"

    private fun entry(participant: String, device: String, updatedAt: Long, call: CallMembership? = null) =
        RosterEntry(participant, device, credential, updatedAt = updatedAt, call = call)

    @Test
    fun `a person on no call is on no call`() {
        val alice = groupByParticipant(listOf(entry("alice", "phone", 10))).single()
        assertNull(alice.call)
        assertTrue(alice.callDevices.isEmpty())
        assertTrue(callsOf(listOf(alice)).isEmpty())
    }

    @Test
    fun `both of a person's devices on one call are one membership`() {
        val alice = groupByParticipant(
            listOf(
                entry("alice", "laptop", 10, CallMembership(CALL, since = 100)),
                entry("alice", "phone", 20, CallMembership(CALL, since = 300)),
            ),
        ).single()

        assertEquals(CALL, alice.call?.id)
        // When the PERSON joined, not when their latest device did.
        assertEquals(100, alice.call?.since)
        assertEquals(setOf("laptop", "phone"), alice.callDevices.toSet())
    }

    @Test
    fun `a call moved to another device follows the fresher entry`() {
        val alice = groupByParticipant(
            listOf(
                entry("alice", "laptop", 10, CallMembership(OTHER, since = 100)),
                entry("alice", "phone", 40, CallMembership(CALL, since = 400)),
            ),
        ).single()

        // One person, one call. The laptop is still heartbeating the old one
        // for a moment and must not make her look like two people.
        assertEquals(CALL, alice.call?.id)
        assertEquals(listOf("phone"), alice.callDevices)
    }

    @Test
    fun `the room's calls are ordered by who is on them`() {
        val people = groupByParticipant(
            listOf(
                entry("alice", "a1", 10, CallMembership(CALL, since = 100)),
                entry("bob", "b1", 10, CallMembership(CALL, since = 150)),
                entry("carol", "c1", 10, CallMembership(OTHER, since = 50)),
            ),
        )

        val calls = callsOf(people)
        assertEquals(2, calls.size)
        // Two people beat one, even though the other call started earlier:
        // this is what makes a room with two accidental calls collapse back
        // to one as people join the bigger.
        assertEquals(CALL, calls.first().id)
        assertEquals(listOf("alice", "bob"), calls.first().participants)
        assertEquals(100, calls.first().since)
        assertEquals(OTHER, calls.last().id)
    }

    @Test
    fun `equal calls are broken by age, so every client picks the same one`() {
        val people = groupByParticipant(
            listOf(
                entry("alice", "a1", 10, CallMembership(CALL, since = 500)),
                entry("bob", "b1", 10, CallMembership(OTHER, since = 400)),
            ),
        )

        // No coordinator decides this. Every client in the room has to reach
        // the same head of the list from the same roster or joiners split.
        assertEquals(OTHER, callsOf(people).first().id)
    }
}
