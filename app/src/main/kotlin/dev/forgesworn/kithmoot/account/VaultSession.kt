package dev.forgesworn.kithmoot.account

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * The vault session a piece of work began in (contract §6.2). Work run under
 * it takes its contexts from here, never afresh: once the account changes
 * ([MlsVault.bump]), every call it makes is stale, however long it has been
 * running. A context taken afresh would be the next session's.
 */
class VaultSession(val context: VaultContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VaultSession>
}

/** [persona]'s context: the running work's session for that persona if it has one, else this session's. */
suspend fun MlsVault.sessionContext(principal: String, persona: String): VaultContext =
    currentCoroutineContext()[VaultSession]?.context?.takeIf { it.principal == principal && it.persona == persona }
        ?: context(principal, persona)
