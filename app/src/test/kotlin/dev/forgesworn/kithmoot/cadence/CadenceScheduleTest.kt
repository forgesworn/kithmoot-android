package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.CadenceReceipt
import dev.forgesworn.kithmoot.storage.RoomStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceScheduleTest {
    @Test fun renewalStartsExactlyWhereTheRunningLeaseEnds() {
        val renewal = CadenceSchedule.renewal(listOf(running), SCOPE, 12, 14, FAR, FAR)

        assertEquals(CadenceRenewal.Ready(LEASE, 2, 22, 34), renewal)
    }

    @Test fun renewalIsCappedByTheCredentialAndTheGrant() {
        assertEquals(CadenceRenewal.Ready(LEASE, 2, 22, 30), CadenceSchedule.renewal(listOf(running), SCOPE, 12, 14, 30, FAR))
        assertEquals(CadenceRenewal.Ready(LEASE, 2, 22, 25), CadenceSchedule.renewal(listOf(running), SCOPE, 12, 14, 30, 25))
        assertRefused("credential or Bothy grant", CadenceSchedule.renewal(listOf(running), SCOPE, 12, 14, 22, FAR))
    }

    @Test fun renewalNeedsTheWholeHandOffEpochBeforeTheEnd() {
        assertEquals(CadenceRenewal.Ready(LEASE, 2, 22, 34), CadenceSchedule.renewal(listOf(running), SCOPE, 20, 22, FAR, FAR))
        assertRefused("Too late", CadenceSchedule.renewal(listOf(running), SCOPE, 21, 23, FAR, FAR))
        assertTrue(CadenceSchedule.renewable(listOf(running), SCOPE, 20))
        assertFalse(CadenceSchedule.renewable(listOf(running), SCOPE, 21))
    }

    @Test fun aLeaseIsRenewedOnlyOnce() {
        assertRefused("already renewed", CadenceSchedule.renewal(listOf(running, renewed), SCOPE, 12, 14, FAR, FAR))
        assertRefused("already renewed", CadenceSchedule.renewal(listOf(running, renewed.unconfirmed()), SCOPE, 12, 14, FAR, FAR))
        assertFalse(CadenceSchedule.renewable(listOf(running, renewed), SCOPE, 12))
    }

    @Test fun aStoppingCoverOrUnconfirmedLeaseIsNotExtended() {
        assertRefused("stopping", CadenceSchedule.renewal(listOf(running.receipt("stopping", "active")), SCOPE, 12, 14, FAR, FAR))
        assertRefused("stopping", CadenceSchedule.renewal(listOf(running.receipt("status", "cover")), SCOPE, 12, 14, FAR, FAR))
        assertRefused("unconfirmed", CadenceSchedule.renewal(listOf(running.unconfirmed()), SCOPE, 12, 14, FAR, FAR))
        assertRefused("Schedule Bothy", CadenceSchedule.renewal(emptyList(), SCOPE, 12, 14, FAR, FAR))
    }

    @Test fun aStagedLeaseCanBeRenewedBeforeItStarts() {
        val staged = running.receipt("staged", "staged")

        assertEquals(CadenceRenewal.Ready(LEASE, 2, 22, 34), CadenceSchedule.renewal(listOf(staged), SCOPE, 8, 10, FAR, FAR))
    }

    @Test fun theGenerationStaysAboveEveryLeaseThisDeviceHeld() {
        val older = lease(OTHER_LEASE, 5, 0, 8).receipt("status", "ended").copy(ownership = CadenceOwnership.ENDED)

        assertEquals(CadenceRenewal.Ready(LEASE, 6, 22, 34), CadenceSchedule.renewal(listOf(older, running), SCOPE, 12, 14, FAR, FAR))
    }

    @Test fun theRunningLeaseIsShownUntilItsRenewalTakesOver() {
        val leases = listOf(renewed, running)

        assertEquals(running, CadenceSchedule.primary(leases, SCOPE, 12))
        assertEquals(renewed, CadenceSchedule.successor(leases, SCOPE, running))
        assertEquals(renewed, CadenceSchedule.primary(leases, SCOPE, 22))
        assertNull(CadenceSchedule.successor(leases, SCOPE, renewed))
        assertEquals("before either starts, the earlier lease", running, CadenceSchedule.primary(leases, SCOPE, 2))
    }

    @Test fun leasesFromAnEarlierRoomKeyOrThatEndedAreNotLive() {
        val earlierKey = lease(OTHER_LEASE, 3, 0, 9).let { it.copy(plan = it.plan.copy(trafficRoom = "43".repeat(32))) }
        val ended = renewed.copy(ownership = CadenceOwnership.ENDED)

        assertEquals(listOf(running), CadenceSchedule.live(listOf(earlierKey, ended, running), SCOPE))
        assertEquals(running, CadenceSchedule.primary(listOf(earlierKey, running), SCOPE, 5))
    }

    @Test fun stopReachesTheRenewalAsWellAsTheRunningLease() {
        assertEquals(listOf(running to 14L, renewed to 22L), CadenceSchedule.stopTargets(listOf(renewed, running), SCOPE, 12))
    }

    @Test fun stopSkipsLeasesItCannotOrNeedNotStop() {
        assertEquals("the running lease ends before a safe boundary", listOf(renewed to 23L),
            CadenceSchedule.stopTargets(listOf(running, renewed), SCOPE, 21))
        assertEquals(listOf(renewed to 22L),
            CadenceSchedule.stopTargets(listOf(running.receipt("stopping", "active"), renewed), SCOPE, 12))
        assertEquals(listOf(renewed to 22L),
            CadenceSchedule.stopTargets(listOf(running.receipt("status", "cover"), renewed), SCOPE, 12))
        assertEquals(listOf(running to 14L),
            CadenceSchedule.stopTargets(listOf(running, renewed.unconfirmed()), SCOPE, 12))
    }

    @Test fun theVaultKeepsBothGenerationsOfOneLeaseWithoutOverlap() {
        val vault = CadenceLeaseVault(MemoryStorage())
        vault.prepare(running.plan, 100)
        vault.prepare(renewed.plan, 101)

        assertEquals(listOf(10L to 22L, 22L to 34L), vault.all(ROOM, DEVICE).map { it.plan.startEpoch to it.plan.endEpoch })
        assertEquals((0 until 8).toList(), vault.reservedCounters(ROOM, DEVICE, 22))
    }

    private fun assertRefused(fragment: String, renewal: CadenceRenewal) {
        assertTrue("$renewal", renewal is CadenceRenewal.Refused && fragment in renewal.reason)
    }

    private fun StoredCadenceLease.receipt(code: String, state: String) = copy(
        ownership = CadenceOwnership.BOX_OWNED,
        receipt = CadenceReceipt(code, plan.leaseId, plan.generation, state, 100, plan.startEpoch, plan.endEpoch, 0, emptyList(), emptyList()),
    )

    private fun StoredCadenceLease.unconfirmed() = copy(ownership = CadenceOwnership.CLIENT_EXCLUDED, receipt = null)

    private class MemoryStorage : RoomStorage {
        var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }

    private companion object {
        const val FAR = Long.MAX_VALUE
        val ROOM = "42".repeat(32)
        val DEVICE = "dd".repeat(32)
        val NODE = "a".repeat(52)
        val LEASE = "22".repeat(16)
        val OTHER_LEASE = "33".repeat(16)
        val SCOPE = CadenceScopeKey(NODE, ROOM, 1)

        fun lease(id: String, generation: Long, start: Long, end: Long) = StoredCadenceLease(
            CadenceLeasePlan(NODE, ROOM, ROOM, 1, DEVICE, id, generation, "%032x".format(generation), "{\"v\":1}", start, end, 0, 8),
            CadenceOwnership.CLIENT_EXCLUDED, null, 100,
        )

        val running = lease(LEASE, 1, 10, 22).let {
            it.copy(ownership = CadenceOwnership.BOX_OWNED, receipt = CadenceReceipt("status", LEASE, 1, "active", 100, 10, 22, 0, emptyList(), emptyList()))
        }
        val renewed = lease(LEASE, 2, 22, 34).let {
            it.copy(ownership = CadenceOwnership.BOX_OWNED, receipt = CadenceReceipt("staged", LEASE, 2, "staged", 100, 22, 34, 0, emptyList(), emptyList()))
        }
    }
}
