package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a VMLS room's invitation travels (P3-03b-3 decision 17): the link's
 * Nostr relays in the app, memory in the lab. Closed when the exchange ends.
 */
interface VmlsCarrier : AutoCloseable {
    /** True once a relay confirmed [event]. */
    suspend fun publish(event: NostrEvent): Boolean

    fun subscribe(filters: List<Filter>): Flow<NostrEvent>
}

/** The link's relays, as today's invitations reach them (`requestAdmission`, `serveInvitation`). */
class RelayCarrier(relays: List<String>, parent: CoroutineScope) : VmlsCarrier {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
    private val pool = RelayPool(relays, OkHttpRelaySockets(), scope).also { it.start() }

    override suspend fun publish(event: NostrEvent): Boolean {
        withTimeoutOrNull(READY_MILLIS) { pool.connected.first { it.isNotEmpty() } } ?: return false
        return runCatching { pool.publishConfirmed(event) }.getOrDefault(false)
    }

    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = pool.subscribe(filters)

    override fun close() {
        pool.stop()
        scope.cancel()
    }

    private companion object { const val READY_MILLIS = 20_000L }
}
