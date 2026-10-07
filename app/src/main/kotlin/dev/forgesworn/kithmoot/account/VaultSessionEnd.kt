package dev.forgesworn.kithmoot.account

/**
 * Ends the vault's session when the account changes: sign-out, or a sign-in
 * over another account (contract §6.2, S25). Every operation started before,
 * and every reply made before, is then stale: a queued VMLS job for the
 * earlier account signs nothing and raises no consent, and its journalled
 * decisions never replay. [end] returns only once the new session epoch is
 * written durably. Release builds carry no vault, so [None] does nothing.
 */
fun interface VaultSessionEnd {
    fun end()

    companion object {
        val None = VaultSessionEnd { }
    }
}
