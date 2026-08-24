package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.TrackRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticipantsTest {

    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))

    private fun entry(
        participant: String,
        device: String,
        tracks: List<TrackRef> = emptyList(),
        claims: Map<String, Long> = emptyMap(),
    ) = RosterEntry(participant, device, credential, tracks, claims, updatedAt = 0)

    @Test
    fun `two devices belonging to one person are one participant`() {
        val roster = listOf(
            entry("alice", "laptop", listOf(TrackRef("t1", Roles.CAMERA))),
            entry("alice", "phone", listOf(TrackRef("t2", Roles.CAMERA))),
            entry("bob", "desktop"),
        )

        val participants = groupByParticipant(roster)

        // This grouping is the product. Alice on two devices is one person to
        // everyone else, not two strangers with the same face.
        assertEquals(2, participants.size)
        val alice = participants.single { it.participant == "alice" }
        assertEquals(2, alice.deviceCount)
        assertEquals(2, alice.tracks.size)
    }

    @Test
    fun `only the arbitration winner's microphone is rendered`() {
        val roster = listOf(
            entry("alice", "laptop", listOf(TrackRef("mic-1", Roles.MIC)), mapOf(Roles.MIC to 100)),
            entry("alice", "phone", listOf(TrackRef("mic-2", Roles.MIC)), mapOf(Roles.MIC to 200)),
        )

        val alice = groupByParticipant(roster).single()

        assertEquals("phone", alice.micDevice)
        // The laptop may still be publishing a stale mic track for a second or
        // two after losing the claim. Playing both is a feedback loop.
        assertEquals(listOf("mic-2"), alice.liveTracks.map { it.trackId })
    }

    @Test
    fun `cameras and screens from several devices are all kept`() {
        val roster = listOf(
            entry("alice", "laptop", listOf(TrackRef("cam", Roles.CAMERA))),
            entry("alice", "tablet", listOf(TrackRef("slides", Roles.SCREEN))),
        )

        val alice = groupByParticipant(roster).single()

        // Additive by design: a face from the laptop and slides from the tablet
        // is the reason a person is on two devices in the first place.
        assertEquals(setOf("cam", "slides"), alice.liveTracks.map { it.trackId }.toSet())
    }

    @Test
    fun `ordering is stable and derived only from pubkeys`() {
        val roster = listOf(
            entry("charlie", "z"),
            entry("alice", "b"),
            entry("alice", "a"),
            entry("bob", "m"),
        )

        val names = groupByParticipant(roster).map { it.participant }
        assertEquals(listOf("alice", "bob", "charlie"), names)
        assertEquals(
            listOf("a", "b"),
            groupByParticipant(roster).first().devices.map { it.device },
        )
        // Every client sorts identically without conferring, so the tile order
        // does not reshuffle on every heartbeat.
        assertTrue(groupByParticipant(roster.reversed()) == groupByParticipant(roster))
    }
}
