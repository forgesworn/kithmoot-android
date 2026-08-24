package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoleArbiterTest {

    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))

    private fun entry(device: String, claims: Map<String, Long>) = RosterEntry(
        participant = "pp".repeat(32),
        device = device,
        credential = credential,
        claims = claims,
        updatedAt = 0,
    )

    @Test
    fun `nobody holds a role nobody claims`() {
        assertNull(RoleArbiter.holder(listOf(entry("aa", emptyMap())), Roles.MIC))
    }

    @Test
    fun `a single claimant holds the role`() {
        val entries = listOf(entry("aa", mapOf(Roles.MIC to 100)), entry("bb", emptyMap()))
        assertEquals("aa", RoleArbiter.holder(entries, Roles.MIC))
    }

    @Test
    fun `the most recent claim wins`() {
        // Picking up your phone should move the microphone to your phone.
        val entries = listOf(entry("aa", mapOf(Roles.MIC to 100)), entry("bb", mapOf(Roles.MIC to 200)))
        assertEquals("bb", RoleArbiter.holder(entries, Roles.MIC))
        assertEquals("bb", RoleArbiter.holder(entries.reversed(), Roles.MIC), "order of arrival must not matter")
    }

    @Test
    fun `a tie is broken on the device pubkey, the same way everywhere`() {
        val entries = listOf(entry("bb", mapOf(Roles.MIC to 100)), entry("aa", mapOf(Roles.MIC to 100)))
        // Arbitrary but total. Every client in the room runs this over the same
        // roster with nobody coordinating, so two devices that claim in the same
        // second must not each conclude they won.
        assertEquals("aa", RoleArbiter.holder(entries, Roles.MIC))
        assertEquals("aa", RoleArbiter.holder(entries.reversed(), Roles.MIC))
    }

    @Test
    fun `mic and monitor are arbitrated separately`() {
        val entries = listOf(
            entry("aa", mapOf(Roles.MIC to 100)),
            entry("bb", mapOf(Roles.MONITOR to 100)),
        )
        assertEquals("aa", RoleArbiter.holder(entries, Roles.MIC))
        assertEquals("bb", RoleArbiter.holder(entries, Roles.MONITOR))
    }
}
