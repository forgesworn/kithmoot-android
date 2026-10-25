package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SIGNER_INTENT_TIMEOUT_MS
import dev.forgesworn.kithmoot.account.SIGNER_SILENT_TIMEOUT_MS
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

/** A single explicit check, unrelated to the MLS device vault's signing approvals. */
class VmlsPrivateInboxConsent(val route: VmlsBoxRoute, private val current: () -> Boolean) {
    private val active = AtomicBoolean(true)
    fun allowed(): Boolean = active.get() && runCatching(current).getOrDefault(false)
    fun withdraw() { active.set(false) }
}

enum class VmlsInboxMode { LATEST, RETAINED }
data class VmlsInboxResult(val book: RevocationBook, val complete: Boolean, val limited: Boolean = false)

/** Supported phase ceilings, injectable downward for deterministic tests.
 * Connection uses the relay's normal open allowance. AUTH separately allows
 * the NIP-55 silent-provider and intent approvals plus a bounded node reply.
 * The stored-page clock starts only after this connection's matching AUTH OK. */
data class VmlsPrivateInboxBudgets(
    val connectMillis: Long = 30_000,
    val authenticationMillis: Long = SIGNER_SILENT_TIMEOUT_MS + SIGNER_INTENT_TIMEOUT_MS + 5_000,
    val pageMillis: Long = 5_000,
) {
    init {
        require(connectMillis in 1..30_000)
        require(authenticationMillis in 1..(SIGNER_SILENT_TIMEOUT_MS + SIGNER_INTENT_TIMEOUT_MS + 5_000))
        require(pageMillis in 1..5_000)
    }
}

/** Only the pinned saved Link socket can be opened. There is no public socket,
 * directory, publication or alternative signer in this reader. Pairing supplies
 * reachability; the node must independently accept the keeper's NIP-42 AUTH. */
class VmlsPrivateInboxReader(
    private val open: (String, String, RelaySocketListener, () -> Boolean) -> RelaySocket,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val budgets: VmlsPrivateInboxBudgets = VmlsPrivateInboxBudgets(),
) {
    suspend fun page(route: VmlsBoxRoute, signer: ParticipantSigner, filter: Filter, current: () -> Boolean): List<NostrEvent>? = coroutineScope {
        require(signer.pubkey == route.persona)
        val url = LinkRelayAddress.canonicalForNode(route.box)
        val failed = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val opened = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val chars = java.util.concurrent.atomic.AtomicLong()
        val live = { !stopped.get() && !failed.get() && runCatching(current).getOrDefault(false) && isActive }
        if (!live()) return@coroutineScope null
        val sockets = RelaySocketFactory { address, listener ->
            check(address == url && live()) { "The selected private route ended" }
            val native = open(address, route.routeId, object : RelaySocketListener {
                override fun onOpen() { if (live()) { listener.onOpen(); opened.complete(Unit) } }
                override fun onMessage(text: String) {
                    if (frames.incrementAndGet() > 128 || text.length > 128_000 || chars.addAndGet(text.length.toLong()) > 3_000_000) {
                        failed.set(true); listener.onClosed("Private inbox frame budget exhausted")
                    } else if (live()) listener.onMessage(text)
                }
                override fun onClosed(reason: String) = listener.onClosed(reason)
            }, live)
            object : RelaySocket {
                override fun send(text: String) { check(live()); native.send(text) }
                override fun close() = native.close()
            }
        }
        val auth = object : RelayAuthenticator {
            override val pubkey = signer.pubkey
            override suspend fun sign(url: String, challenge: String): NostrEvent {
                check(live())
                return signer.sign(22242, now(), listOf(listOf("relay", url), listOf("challenge", challenge)), "").also { check(live()) }
            }
        }
        val poolScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val pool = RelayPool(listOf(url), sockets, poolScope, circle = { setOf(url) },
            authenticators = RelayAuthenticatorProvider { address -> auth.takeIf { address == url && live() } }, writeRelays = emptySet())
        val guard = launch { while (live()) delay(25); ended.complete(Unit); pool.stop() }
        // Consent loss wakes a phase wait promptly, even if no network frame
        // arrives. Cancelling the caller still propagates to all phase work.
        suspend fun <T> whileAllowed(work: suspend () -> T): T? = coroutineScope {
            val pending = async { work() }
            try { select { pending.onAwait { it }; ended.onAwait { null } } }
            finally { pending.cancel() }
        }
        try {
            check(live()); pool.start()
            val connected = whileAllowed {
                withTimeoutOrNull(budgets.connectMillis) { opened.await(); true } ?: false
            } == true
            if (!connected || !live()) return@coroutineScope null
            // Socket-open is not authority. No REQ before matching AUTH OK.
            val authenticated = whileAllowed { pool.awaitAuthentication(url, budgets.authenticationMillis) } == true
            if (!authenticated || !live()) return@coroutineScope null
            whileAllowed {
                withTimeoutOrNull(budgets.pageMillis) {
                    pool.queryStored(listOf(filter), budgets.pageMillis).also { check(live()) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        finally { stopped.set(true); ended.complete(Unit); guard.cancel(); pool.stop(); poolScope.cancel() }
    }
}

internal fun privateCursorKey(route: VmlsBoxRoute): String = kotlinx.serialization.json.buildJsonArray {
    add(kotlinx.serialization.json.JsonPrimitive(route.persona))
    add(kotlinx.serialization.json.JsonPrimitive(route.box))
    add(kotlinx.serialization.json.JsonPrimitive(route.routeId))
}.toString()
