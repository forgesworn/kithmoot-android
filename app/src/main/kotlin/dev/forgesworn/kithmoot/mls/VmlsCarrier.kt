package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a VMLS room's invitation travels (P3-03b-3 decision 17): the link's
 * Nostr relays in the app, memory in the lab. Closed when the exchange ends.
 */
interface VmlsCarrier : AutoCloseable {
    /** True once a relay confirmed [event]. */
    suspend fun publish(event: NostrEvent): Boolean

    fun subscribe(filters: List<Filter>): Flow<NostrEvent>

    /** One bounded stored page; null means incomplete, so its cursor must not advance. */
    suspend fun readPage(filter: Filter): List<NostrEvent>? = withTimeoutOrNull(5_000) {
        subscribe(listOf(filter)).take(filter.limit ?: 64).toList()
    }
}

/** The link's relays, as today's invitations reach them (`requestAdmission`, `serveInvitation`). */
class RelayCarrier(relays: List<String>, parent: CoroutineScope, signer: ParticipantSigner? = null, sockets: dev.forgesworn.kithmoot.relay.RelaySocketFactory = OkHttpRelaySockets()) : VmlsCarrier {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
    private val pool = RelayPool(relays, sockets, scope, authenticators = RelayAuthenticatorProvider { url ->
        signer?.takeIf { url in relays }?.let { actor -> object : RelayAuthenticator {
            override val pubkey = actor.pubkey
            override suspend fun sign(url: String, challenge: String) = actor.sign(22242, System.currentTimeMillis() / 1000,
                listOf(listOf("relay", url), listOf("challenge", challenge)), "")
        } }
    }, publicAuthOnChallenge = signer != null).also { it.start() }

    override suspend fun publish(event: NostrEvent): Boolean {
        withTimeoutOrNull(READY_MILLIS) { pool.connected.first { it.isNotEmpty() } } ?: return false
        return runCatching { pool.publishConfirmed(event) }.getOrDefault(false)
    }

    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = pool.subscribe(filters)

    override suspend fun readPage(filter: Filter): List<NostrEvent>? = try {
        withTimeoutOrNull(5_000) {
            // StoredQuery verifies signatures before ID dedup and completes on EOSE.
            val events = try { pool.queryStored(listOf(filter), 5_000) }
                catch (refused: dev.forgesworn.kithmoot.relay.RelayHistoryException) {
                    if (!refused.authenticationRequired || !pool.awaitAuthentication(refused.relay, 5_000)) return@withTimeoutOrNull null
                    // Retry once after verified AUTH OK, under the same overall deadline.
                    pool.queryStored(listOf(filter), 5_000)
                }
            events.sortedByDescending { it.createdAt }.take(filter.limit ?: 64)
        }
    } catch (_: TimeoutCancellationException) { null }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    override fun close() {
        pool.stop()
        scope.cancel()
    }

    private companion object { const val READY_MILLIS = 20_000L }
}
