package dev.forgesworn.kithmoot.account

/**
 * Ends the vault's session when the account changes: sign-out, or a sign-in
 * over another account (contract §6.2, S25). Every operation started before,
 * and every reply made before, is then stale: VMLS actions and rounds begun
 * for the earlier account, queued or running, take their vault contexts from
 * the session they began in ([VaultSession]), so from then on the vault
 * signs nothing and journals nothing for them; an ask still showing is
 * withdrawn; and journalled decisions never replay. [end] returns once the new session epoch is written durably,
 * or has failed to be (the process still cancels). [None] does nothing, for
 * callers with no vault.
 */
fun interface VaultSessionEnd {
    fun end()

    companion object {
        val None = VaultSessionEnd { }
    }
}
