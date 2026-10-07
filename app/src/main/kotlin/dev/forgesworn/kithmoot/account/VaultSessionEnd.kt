package dev.forgesworn.kithmoot.account

/**
 * Ends the vault's session when the account changes: sign-out, or a sign-in
 * over another account (contract §6.2, S25). Every operation started before,
 * and every reply made before, is then stale: VMLS work begun for the earlier
 * account, queued or running, takes its vault contexts from the session it
 * began in ([VaultSession]), so from then on it signs nothing and journals
 * nothing; an ask still showing is withdrawn; and its journalled decisions
 * never replay. [end] returns once the new session epoch is written durably,
 * or has failed to be (the process still cancels). Release builds carry no
 * vault, so [None] does nothing.
 */
fun interface VaultSessionEnd {
    fun end()

    companion object {
        val None = VaultSessionEnd { }
    }
}
