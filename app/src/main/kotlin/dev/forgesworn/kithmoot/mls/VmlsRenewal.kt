package dev.forgesworn.kithmoot.mls

/**
 * When the foreground rounds renew a VMLS credential or grant (P3-03b-3d),
 * as pure decisions the runtime acts on.
 */
object VmlsRenewal {
    /** A device credential with this long or less left is renewed; the engine's 25-hour floor is well beyond it. */
    const val CREDENTIAL_RENEW_SECONDS = 7L * 86_400

    /** A refused or denied renewal is not asked again for this long. */
    const val CREDENTIAL_RETRY_SECONDS = 86_400L

    /** Whether a credential expiring at [expiresAt] is due for renewal at [now]. */
    fun credentialDue(expiresAt: Long, now: Long): Boolean = expiresAt - now <= CREDENTIAL_RENEW_SECONDS

    /**
     * The grants [persona] issued at [box] to a device in [inUse] that expire
     * within [window] of [boxNow] (by the box's clock). Revoking, revoked and
     * removed grants are left to lapse: a device out of the rooms is not kept.
     */
    fun grantsDue(records: List<VmlsGrantRecord>, persona: String, box: String, inUse: Set<String>, boxNow: Long, window: Long): List<VmlsGrantRecord> =
        records.filter {
            it.box == box && it.issuer == persona && it.device in inUse && it.state == VmlsGrantState.ACTIVE &&
                it.removedAt == null && it.expiration - boxNow <= window
        }
}
