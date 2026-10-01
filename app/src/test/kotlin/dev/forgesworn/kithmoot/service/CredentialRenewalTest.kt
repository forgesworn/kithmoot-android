package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.storage.RING_CREDENTIAL_RENEW_BELOW
import dev.forgesworn.kithmoot.storage.RING_CREDENTIAL_TTL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRenewalTest {
    private val now = 1_800_000_000L
    private val hour = 60L * 60
    private val day = 24 * hour
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
    fun `renews a Ring me room once it is under three and a half days or has nothing kept`() {
        val rooms = listOf(room("fresh", 6 * day), room("half", 3 * day), room("none", null))
        assertEquals(listOf("half", "none"), roomsDueForRenewal(rooms, me, now))
    }

    @Test
    fun `a week-long credential leaves days of quiet tries before anyone is told`() {
        // Due from day three and a half, at risk only in the last two hours.
        val due = RING_CREDENTIAL_TTL - RING_CREDENTIAL_RENEW_BELOW
        assertEquals(RING_CREDENTIAL_TTL / 2, RING_CREDENTIAL_RENEW_BELOW)
        assertTrue(due > 3 * day)
        assertTrue(RING_CREDENTIAL_RENEW_BELOW - AT_RISK_SECONDS > 3 * day)
        assertEquals(listOf("a"), roomsDueForRenewal(listOf(room("a", RING_CREDENTIAL_RENEW_BELOW - 1)), me, now))
        assertEquals(emptyList<String>(), roomsAtRisk(listOf(room("a", RING_CREDENTIAL_RENEW_BELOW - 1)), me, now))
    }

    @Test
    fun `credentials for Ring me rooms last a week and every other room's a day`() {
        assertEquals(7 * day, credentialLifetimeFor(true, CallRingMode.RING))
        assertEquals(24 * hour, credentialLifetimeFor(true, CallRingMode.QUIET))
        assertEquals(24 * hour, credentialLifetimeFor(true, CallRingMode.NOTHING))
        assertEquals(24 * hour, credentialLifetimeFor(false, CallRingMode.RING))
    }

    @Test
    fun `an account that cannot be read counts every room joined as one`() {
        val rooms = listOf(room("mine", hour), room("theirs", hour, participant = "b".repeat(64)), room("local", hour, viaAccount = false))
        assertEquals(listOf("mine", "theirs"), roomsAtRisk(rooms, null, now))
    }

    @Test
    fun `names the rooms that cannot ring, and only those`() {
        val rooms = listOf(room("soon", hour), room("fine", 2 * day), room("quiet", null, mode = CallRingMode.QUIET), room("expired", null))
        assertEquals(listOf("soon", "expired"), roomsAtRisk(rooms, me, now))
        assertEquals(emptyList<String>(), roomsAtRisk(rooms, "b".repeat(64), now))
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
