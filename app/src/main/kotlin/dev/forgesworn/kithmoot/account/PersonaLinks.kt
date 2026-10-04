package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkTransportRuntime
import dev.forgesworn.kithmoot.relay.LinkTransportSession
import dev.forgesworn.kithmoot.relay.LinkTransportState
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Each persona's own Link engine for its witness traffic (P3-03b-2, C1). The
 * engine runs on the persona's writer seed, never the app's shared Link
 * identity, so the box sees the persona's writer and nothing else; its one
 * route is the keeper's witness-only booking.
 *
 * One engine per persona, started on first use and restarted only when its
 * seed or route changes. Native calls run on one serial worker, like
 * [dev.forgesworn.kithmoot.relay.LinkTransportManager]'s. While [quiet] is
 * true (a Tor-only room is open, C7) no channel is given and no request is
 * sent, so covered writes hold.
 */
class PersonaLinks(
    private val runtime: LinkTransportRuntime,
    private val quiet: () -> Boolean,
    private val timeoutMillis: Long = WitnessLink.DEFAULT_TIMEOUT_MILLIS,
    private val random: SecureRandom = SecureRandom(),
    private val worker: Executor = defaultWorker(),
) : WitnessChannels, AutoCloseable {
    private class Held(val key: String, val session: LinkTransportSession)

    /** Touched only on [worker]. */
    private val sessions = HashMap<String, Held>()
    @Volatile private var closed = false

    override fun forPersona(persona: String, writerSeed: ByteArray?, route: StoredLinkRoute?): WitnessChannel? {
        if (closed || quiet()) return null
        val seed = writerSeed ?: return null
        val paired = route ?: return null
        val key = sessionKey(seed, paired)
        val state = LinkTransportState(seed.copyOf(), listOf(paired.copy(card = paired.card.copyOf(), pairedRouteSecret = paired.pairedRouteSecret.copyOf())))
        if (!submit({ ensure(persona, key, state) }) { state.transportSeed.fill(0) }) return null
        return WitnessLink(LinkJsonTransport { request -> send(persona, key, request) }, paired.routeId, timeoutMillis)
    }

    /**
     * Scans the keeper's witness-only pairing code into [persona]'s engine,
     * started from [writerSeed] (copied; the caller wipes its own), and
     * answers the booked route. The pairing secret is wiped afterwards.
     */
    fun pair(persona: String, writerSeed: ByteArray, pairing: BothyPairing): CompletableFuture<StoredLinkRoute> {
        val result = CompletableFuture<StoredLinkRoute>()
        if (closed || quiet()) {
            pairing.pairingSecret.fill(0)
            return result.also { it.completeExceptionally(IllegalStateException("Witness traffic is paused while a Tor-only room is open")) }
        }
        val state = LinkTransportState(writerSeed.copyOf(), emptyList())
        val unrouted = sessionKey(writerSeed, null)
        val seedHash = seedHash(writerSeed)
        val routeId = "witness-" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        val submitted = submit({
            try {
                check(!closed && !quiet()) { "Witness traffic is paused while a Tor-only room is open" }
                ensure(persona, unrouted, state)
                val held = sessions.getValue(persona)
                val route = held.session.pair(routeId, pairing.card, pairing.pairingSecret, pairing.expiresAt.toULong())
                // The engine now holds the route: keep it for the persona's first witness request.
                sessions[persona] = Held(sessionKey(seedHash, route), held.session)
                result.complete(route)
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }) {
            state.transportSeed.fill(0)
            pairing.pairingSecret.fill(0)
        }
        if (!submitted) result.completeExceptionally(IllegalStateException("The witness link has stopped"))
        return result
    }

    /** Stops [persona]'s engine, after its installation is retired or replaced. */
    fun forget(persona: String) {
        submit({ sessions.remove(persona)?.let { runCatching { it.session.stop() } } }) {}
    }

    override fun close() {
        if (closed) return
        closed = true
        submit({
            sessions.values.forEach { runCatching { it.session.stop() } }
            sessions.clear()
            (worker as? ExecutorService)?.shutdown()
        }) {}
    }

    private fun send(persona: String, key: String, request: LinkJsonRequest): CompletableFuture<LinkJsonResponse> {
        val result = CompletableFuture<LinkJsonResponse>()
        val submitted = submit({
            try {
                check(!closed && !quiet()) { "Witness traffic is paused while a Tor-only room is open" }
                val held = sessions[persona]?.takeIf { it.key == key } ?: throw IllegalStateException("The witness link did not start")
                result.complete(held.session.request(request.copy(body = request.body.copyOf())))
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }) {}
        if (!submitted) result.completeExceptionally(IllegalStateException("The witness link has stopped"))
        return result
    }

    /** On the worker: the persona's engine for [key], starting it afresh when the seed or route changed. */
    private fun ensure(persona: String, key: String, state: LinkTransportState) {
        check(!closed) { "The witness link has stopped" }
        val held = sessions[persona]
        if (held?.key == key) return
        held?.let { sessions.remove(persona); runCatching { it.session.stop() } }
        sessions[persona] = Held(key, runtime.start(state))
    }

    /** Runs [work] on the worker, then [always]; false (after [always]) when the worker refuses it. */
    private fun submit(work: () -> Unit, always: () -> Unit): Boolean = try {
        worker.execute { try { work() } catch (_: Exception) {} finally { always() } }
        true
    } catch (_: RejectedExecutionException) {
        always()
        false
    }

    private companion object {
        fun defaultWorker(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kithmoot-witness-link").apply { isDaemon = true }
        }

        /** Never the seed itself: a hash names which engine is running. */
        fun seedHash(seed: ByteArray): String = Digests.sha256("kithmoot.witness-link.seed|".toByteArray(Charsets.US_ASCII) + seed).toHex()

        fun sessionKey(seed: ByteArray, route: StoredLinkRoute?): String = sessionKey(seedHash(seed), route)

        fun sessionKey(seedHash: String, route: StoredLinkRoute?): String {
            if (route == null) return seedHash
            val routeHash = Digests.sha256(
                route.routeId.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + route.card + route.pairedRouteSecret,
            ).toHex()
            return "$seedHash|$routeHash"
        }
    }
}
