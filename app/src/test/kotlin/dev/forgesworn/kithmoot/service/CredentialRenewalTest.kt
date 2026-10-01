package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRenewalTest {
    private val now = 1_800_000_000L
    private val hour = 60L * 60
    private val me = "a".repeat(64)

    private fun room(
        id: String,
        expiresIn: Long?,
        viaAccount: Boolean = true,
        participant: String = me,
        excluded: Boolean = false,
        mode: CallRingMode = CallRingMode.RING,
    ) = RenewalCandidate(id, viaAccount, participant, excluded, mode, expiresIn?.let { now + it })

    @Test
    fun `renews a Ring me room once it is under twelve hours or has nothing kept`() {
        val rooms = listOf(room("fresh", 20 * hour), room("half", 11 * hour), room("none", null))
        assertEquals(listOf("half", "none"), roomsDueForRenewal(rooms, me, now))
    }

    @Test
    fun `leaves rooms that never need the account signer for an answer`() {
        val rooms = listOf(
            room("local", null, viaAccount = false),
            room("other account", null, participant = "b".repeat(64)),
            room("anonymous or ended", null, excluded = true),
            room("quiet", null, mode = CallRingMode.QUIET),
            room("silent", null, mode = CallRingMode.NOTHING),
        )
        assertEquals(emptyList<String>(), roomsDueForRenewal(rooms, me, now))
    }

    @Test
    fun `warns only when an answer is close to needing the signer`() {
        assertFalse(reachabilityAtRisk(listOf(room("due but fine", 6 * hour)), me, now))
        assertTrue(reachabilityAtRisk(listOf(room("soon", hour)), me, now))
        assertTrue(reachabilityAtRisk(listOf(room("expired", null)), me, now))
        assertFalse(reachabilityAtRisk(listOf(room("quiet", null, mode = CallRingMode.QUIET)), me, now))
    }
}
