package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.session.groupByParticipant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tests that guard the product.
 *
 * Everything here is about one claim: a person on two devices renders as **one**
 * tile group. It is checked at this layer, and not only in `Participants`,
 * because the grouping can be perfectly correct in the session and still be
 * thrown away by the thing that draws it - which is exactly the failure these
 * tests exist to catch.
 */
class TilesTest {

    private val credential =
        NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))

    private fun entry(
        participant: String,
        device: String,
        tracks: List<TrackRef> = emptyList(),
        claims: Map<String, Long> = emptyMap(),
    ) = RosterEntry(participant, device, credential, tracks, claims, updatedAt = 0)

    private fun tiles(
        roster: List<RosterEntry>,
        self: String = "alice",
        selfDevice: String = "laptop",
    ) = buildTiles(groupByParticipant(roster), self, selfDevice)

    @Test
    fun `one person on two devices is one tile group`() {
        val result = tiles(
            listOf(
                entry("alice", "laptop", listOf(TrackRef("t1", Roles.CAMERA))),
                entry("alice", "phone", listOf(TrackRef("t2", Roles.SCREEN))),
                entry("bob", "desktop", listOf(TrackRef("t3", Roles.CAMERA))),
            ),
        )

        assertEquals(2, result.size)
        val alice = result.single { it.participant == "alice" }
        assertEquals(2, alice.deviceCount)
        // Both of her devices' tracks land in her one tile group.
        assertEquals(2, alice.videos.size)
        assertEquals(setOf("laptop", "phone"), alice.videos.map { it.device }.toSet())
    }

    @Test
    fun `a screen share is shown before a camera`() {
        val alice = tiles(
            listOf(
                entry("alice", "laptop", listOf(TrackRef("cam", Roles.CAMERA))),
                entry("alice", "phone", listOf(TrackRef("scr", Roles.SCREEN))),
            ),
        ).single()

        assertEquals(Roles.SCREEN, alice.videos.first().role)
        assertTrue(alice.isSharingScreen)
    }

    @Test
    fun `only the microphone the room can actually hear is reported`() {
        val alice = tiles(
            listOf(
                entry(
                    "alice",
                    "laptop",
                    listOf(TrackRef("m1", Roles.MIC)),
                    mapOf(Roles.MIC to 10),
                ),
                entry(
                    "alice",
                    "phone",
                    listOf(TrackRef("m2", Roles.MIC)),
                    mapOf(Roles.MIC to 20),
                ),
            ),
        ).single()

        // The phone claimed later, so it holds the microphone and the laptop's
        // stale track is not counted as a second live mic.
        assertEquals("phone", alice.micDevice)
        assertFalse(alice.micIsThisDevice)
    }

    @Test
    fun `the tile says when the live microphone is this very device`() {
        val alice = tiles(
            listOf(entry("alice", "laptop", emptyList(), mapOf(Roles.MIC to 5))),
            selfDevice = "laptop",
        ).single()

        assertTrue(alice.micIsThisDevice)
        assertTrue(alice.hasMic)
    }

    @Test
    fun `microphones are never audio tiles`() {
        val alice = tiles(
            listOf(entry("alice", "laptop", listOf(TrackRef("m1", Roles.MIC)), mapOf(Roles.MIC to 1))),
        ).single()

        // An audio track has nothing to render. It shows as a chip, not a pane.
        assertTrue(alice.videos.isEmpty())
        assertFalse(alice.hasVideo)
    }

    @Test
    fun `you come first and everyone else is in a stable order`() {
        val roster = listOf(
            entry("carol", "c1"),
            entry("alice", "laptop"),
            entry("bob", "b1"),
        )

        val order = tiles(roster).map { it.participant }

        assertEquals(listOf("alice", "bob", "carol"), order)
        assertTrue(tiles(roster.reversed()).map { it.participant } == order)
    }

    @Test
    fun `a person with no devices claiming a microphone has none`() {
        val bob = tiles(listOf(entry("bob", "b1"))).single()

        assertNull(bob.micDevice)
        assertFalse(bob.hasMic)
        assertFalse(bob.isSelf)
    }

    @Test
    fun `a shortened key shows both ends`() {
        val key = "00a0b8b578de367e65c400cccdb7743e82403d457469d02023b1568a92faadd8"

        val short = shortId(key)

        assertTrue(short.startsWith("00a0b8"))
        assertTrue(short.endsWith("add8"))
        // Both ends, because a shared prefix is cheap to grind and a shared
        // prefix *and* suffix is not.
        assertEquals("00a0b8…add8", short)
    }
}
