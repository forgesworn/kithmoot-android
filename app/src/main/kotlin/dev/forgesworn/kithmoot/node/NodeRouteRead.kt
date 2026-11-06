package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.media.NodeCallLease

/** Even a successful retry must discard route evidence collected while offline. */
internal suspend fun <T> readNodeForRoute(lease: NodeCallLease, read: suspend () -> T): T =
    retryNodeRead {
        try { read() }
        catch (failure: NodeRequestException) {
            if (failure.retryable) lease.accessUnavailable()
            throw failure
        }
    }
