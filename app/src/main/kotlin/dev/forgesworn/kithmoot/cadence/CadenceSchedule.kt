package dev.forgesworn.kithmoot.cadence

/** The Bothy scope a room's current key delegates to. */
data class CadenceScopeKey(val nodeId: String, val trafficRoom: String, val roomGeneration: Long)

sealed interface CadenceRenewal {
    /** Stage the same lease id at the next generation, starting exactly where the current lease ends. */
    data class Ready(val leaseId: String, val generation: Long, val startEpoch: Long, val endEpoch: Long) : CadenceRenewal
    data class Refused(val reason: String) : CadenceRenewal
}

/**
 * Which of a device's leases the room shows, renews and stops. A renewal is a
 * second, contiguous lease, so up to two can be live under one room key; the
 * one covering the current epoch is the one Bothy is running.
 */
object CadenceSchedule {
    const val MAX_LEASE_EPOCHS = 12L

    /** Live leases under this room key, earliest first. Leases from an earlier key are left to finish their cover. */
    fun live(leases: List<StoredCadenceLease>, scope: CadenceScopeKey): List<StoredCadenceLease> = leases
        .filter {
            it.ownership != CadenceOwnership.ENDED && it.plan.nodeId == scope.nodeId &&
                it.plan.trafficRoom == scope.trafficRoom && it.plan.roomGeneration == scope.roomGeneration
        }
        .sortedWith(compareBy({ it.plan.startEpoch }, { it.plan.generation }))

    /** The lease covering [epoch], else the earliest live one. */
    fun primary(leases: List<StoredCadenceLease>, scope: CadenceScopeKey, epoch: Long): StoredCadenceLease? {
        val live = live(leases, scope)
        return live.firstOrNull { epoch in it.plan.startEpoch until it.plan.endEpoch } ?: live.firstOrNull()
    }

    /** A live lease that begins at or after [lease] ends. */
    fun successor(leases: List<StoredCadenceLease>, scope: CadenceScopeKey, lease: StoredCadenceLease): StoredCadenceLease? =
        live(leases, scope).firstOrNull { it.plan.startEpoch >= lease.plan.endEpoch }

    /**
     * Renewal is always the person's explicit choice, never a timer. A lease
     * that is stopping, in cover or unresolved is not extended: it ends at
     * the boundary it promised.
     */
    fun renewal(
        /** Every lease this device holds for the room, so the generation stays above all of them. */
        leases: List<StoredCadenceLease>,
        scope: CadenceScopeKey,
        epoch: Long,
        earliestStartEpoch: Long,
        credentialExpiryEpoch: Long,
        grantExpiryEpoch: Long,
    ): CadenceRenewal {
        val current = primary(leases, scope, epoch) ?: return CadenceRenewal.Refused("Schedule Bothy before renewing.")
        if (current.ownership != CadenceOwnership.BOX_OWNED) {
            return CadenceRenewal.Refused("Bothy's reply to the current schedule is unconfirmed. Retry it before renewing.")
        }
        val receipt = current.receipt
        if (receipt?.code == "stopping" || receipt?.state !in setOf("staged", "active")) {
            return CadenceRenewal.Refused("This schedule is stopping. It ends at its boundary and is not extended.")
        }
        if (successor(leases, scope, current) != null) {
            return CadenceRenewal.Refused("This schedule is already renewed.")
        }
        val start = current.plan.endEpoch
        if (start < earliestStartEpoch) {
            return CadenceRenewal.Refused("Too late to renew. This schedule ends at its boundary; schedule Bothy again after it ends.")
        }
        val end = minOf(start + MAX_LEASE_EPOCHS, credentialExpiryEpoch, grantExpiryEpoch)
        if (end <= start) {
            return CadenceRenewal.Refused("This device's room credential or Bothy grant ends with this schedule. Reopen the room and reconnect Bothy to renew.")
        }
        val generation = leases.maxOf { it.plan.generation } + 1
        return CadenceRenewal.Ready(current.plan.leaseId, generation, start, end)
    }

    /** Whether to offer Renew; the expiry caps are checked when it is pressed. */
    fun renewable(leases: List<StoredCadenceLease>, scope: CadenceScopeKey, epoch: Long): Boolean =
        renewal(leases, scope, epoch, epoch + 2, Long.MAX_VALUE, Long.MAX_VALUE) is CadenceRenewal.Ready

    /**
     * Every live lease Bothy still owns gets its own stop boundary, so a
     * staged renewal never outlives a stop of the lease before it.
     */
    fun stopTargets(leases: List<StoredCadenceLease>, scope: CadenceScopeKey, epoch: Long): List<Pair<StoredCadenceLease, Long>> =
        live(leases, scope).mapNotNull { lease ->
            val receipt = lease.receipt
            if (lease.ownership != CadenceOwnership.BOX_OWNED || receipt?.code == "stopping" ||
                receipt?.state in setOf("cover", "ended")) return@mapNotNull null
            val boundary = maxOf(epoch + 2, lease.plan.startEpoch)
            if (boundary > lease.plan.endEpoch) null else lease to boundary
        }
}
