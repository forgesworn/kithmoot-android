package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.session.callsOf
import dev.forgesworn.kithmoot.session.groupByParticipant
import dev.forgesworn.kithmoot.session.starter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundRosterTest {
    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))
    private fun entry(participant: String, device: String, updatedAt: Long, call: CallMembership? = null) =
        RosterEntry(participant, device, credential, updatedAt = updatedAt, call = call)

    @Test
    fun `a call becomes visible once its device's entry arrives`() {
        val roster = BackgroundRoster(ttlSeconds = 75)
        val call = CallMembership("c0ffeec0ffeec0ffeec0ffeec0ffeec0", since = 100)
        roster.accept(entry("alice", "phone", 100, call), now = 100)

        val people = groupByParticipant(roster.current(now = 100))
        val current = callsOf(people).firstOrNull()

        assertEquals("c0ffeec0ffeec0ffeec0ffeec0ffeec0", current?.id)
        assertEquals("alice", current?.starter(people))
    }

    @Test
    fun `an entry older than the TTL no longer counts`() {
        val roster = BackgroundRoster(ttlSeconds = 75)
        val call = CallMembership("c0ffeec0ffeec0ffeec0ffeec0ffeec0", since = 100)
        roster.accept(entry("alice", "phone", 100, call), now = 100)

        assertTrue(roster.current(now = 100 + 76).isEmpty())
        val people = groupByParticipant(roster.current(now = 100 + 76))
        assertNull(callsOf(people).firstOrNull())
    }

    @Test
    fun `a fresher entry from the same device replaces the stale one`() {
        val roster = BackgroundRoster(ttlSeconds = 75)
        roster.accept(entry("alice", "phone", 10), now = 10)
        roster.accept(entry("alice", "phone", 20), now = 20)

        assertEquals(1, roster.current(now = 20).size)
        assertEquals(20, roster.current(now = 20).single().updatedAt)
    }
}
