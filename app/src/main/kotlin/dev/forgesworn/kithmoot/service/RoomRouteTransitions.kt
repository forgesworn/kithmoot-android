package dev.forgesworn.kithmoot.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialises route changes with background handoffs and credential renewal.
 * Read the saved route inside this gate, not before waiting for it. A route
 * change also drains the background service's shared queues before committing.
 * Already transmitted bytes cannot be recalled by changing a preference. */
internal object RoomRouteTransitions {
    private val gate = Mutex()
    suspend fun <T> stable(action: suspend () -> T): T = gate.withLock { action() }
}
