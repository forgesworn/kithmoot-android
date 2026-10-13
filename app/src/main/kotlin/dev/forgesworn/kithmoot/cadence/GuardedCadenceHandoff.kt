package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.NostrEvent
import java.util.concurrent.CompletableFuture

/** Queue completion means custody only. Its owner retains the exact recording
 * request and resolves cancellation through the original node's journal. */
interface GuardedCadenceHandoff {
    fun queue(lease: StoredCadenceLease, event: NostrEvent, publishBefore: Long,
        stillAllowed: () -> Boolean): CompletableFuture<Unit>
    fun confirmed(event: NostrEvent)
    fun revoked(event: NostrEvent)
}
