package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent

/** Delegating transports preserve the original absolute authority window.
 * The caller's cheap guard owns its clock and current authority; an offloaded
 * publisher must additionally enforce this deadline on its own clock. */
interface BoundedRoomTransport : RoomTransport {
    /** [retryRestricted] is a deliberate re-offer of a previously restricted chat event. */
    suspend fun publishConfirmedBounded(event: NostrEvent, generation: Long, publishBefore: Long,
        stillAllowed: () -> Boolean, timeoutMs: Long, retryRestricted: Boolean = false): Boolean
}

/** Plain publishers retain their guarded dispatch contract. A transparent
 * delegator implements BoundedRoomTransport to forward the deadline. */
suspend fun RoomTransport.publishConfirmedBounded(event: NostrEvent, generation: Long,
    publishBefore: Long, stillAllowed: () -> Boolean, timeoutMs: Long, retryRestricted: Boolean = false): Boolean {
    require(publishBefore > 0)
    return if (this is BoundedRoomTransport)
        publishConfirmedBounded(event, generation, publishBefore, stillAllowed, timeoutMs, retryRestricted)
    else publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
}
