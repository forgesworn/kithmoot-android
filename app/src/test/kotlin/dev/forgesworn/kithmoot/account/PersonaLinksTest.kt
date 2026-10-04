package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkPathState
import dev.forgesworn.kithmoot.relay.LinkTransportRuntime
import dev.forgesworn.kithmoot.relay.LinkTransportSession
import dev.forgesworn.kithmoot.relay.LinkTransportSocket
import dev.forgesworn.kithmoot.relay.LinkTransportState
import dev.forgesworn.kithmoot.relay.RelaySocketListener
import dev.forgesworn.kithmoot.relay.StoredLinkRoute
import java.util.Base64
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Each persona's own Link engine for its witness traffic (P3-03b-2 PR 5). */
class PersonaLinksTest {
    private class FakeSession(val seed: ByteArray, val routes: List<StoredLinkRoute>) : LinkTransportSession {
        val requests = mutableListOf<LinkJsonRequest>()
        var paired: Triple<String, ByteArray, ULong>? = null
        var stopped = false
        var answer = LinkJsonResponse(403, ByteArray(0), LinkPathState("direct", null, null, ""), witnessRefused = true)

        override fun request(request: LinkJsonRequest): LinkJsonResponse { requests += request; return answer }
        override fun pair(routeId: String, card: ByteArray, pairingSecret: ByteArray, expiresAt: ULong): StoredLinkRoute {
            paired = Triple(routeId, pairingSecret.copyOf(), expiresAt)
            return StoredLinkRoute(routeId, card.copyOf(), ByteArray(32) { 9 }, 1uL, NOW.toULong())
        }
        override fun stop() { stopped = true }
        override fun open(url: String, routeId: String, listener: RelaySocketListener): LinkTransportSocket = throw UnsupportedOperationException()
        override fun upsert(route: StoredLinkRoute) = Unit
        override fun retire(routeId: String) = Unit
        override fun finalize(routeId: String) = Unit
        override fun remove(routeId: String) = Unit
    }

    private val started = mutableListOf<FakeSession>()
    private val runtime = object : LinkTransportRuntime {
        override fun start(state: LinkTransportState): LinkTransportSession =
            FakeSession(state.transportSeed.copyOf(), state.routes.map { it.copy(card = it.card.copyOf()) }).also { started += it }
    }
    private var quiet = false
    /** Idle stops waiting to run, as the timer would run them. */
    private val idle = mutableListOf<() -> Unit>()
    /** Pairings waiting for their own thread. */
    private val pairings = mutableListOf<Runnable>()
    // Runs every native call inline, so each test reads in order.
    private val links = PersonaLinks(
        runtime, quiet = { quiet }, worker = { it.run() },
        later = { _, task -> idle += task }, pairer = { pairings += it },
    )

    private val seed = ByteArray(32) { 5 }
    private val route = StoredLinkRoute("witness-a", WitnessEnrolmentTest.CARD, ByteArray(32) { 3 }, 1uL, NOW.toULong())

    @Test fun `no channel while quiet, before pairing or without a writer, and no engine is started`() {
        quiet = true
        assertNull(links.forPersona(PERSONA, seed, route))
        quiet = false
        assertNull(links.forPersona(PERSONA, seed, null))
        assertNull(links.forPersona(PERSONA, null, route))
        assertTrue(started.isEmpty())
    }

    @Test fun `the engine runs on the persona's own seed and its one witness route`() = runBlocking<Unit> {
        val channel = links.forPersona(PERSONA, seed, route)!!
        assertEquals(WitnessAnswer.Refused, channel.read(byteArrayOf(1)))
        val session = started.single()
        assertContentEquals(seed, session.seed)
        assertEquals(listOf("witness-a"), session.routes.map { it.routeId })
        val request = session.requests.single()
        assertEquals("witness-a", request.routeId)
        assertEquals(WitnessLink.READ_PATH, request.path)
        assertEquals("", request.authorization)
    }

    @Test fun `one engine per persona, restarted only when its seed or route changes`() = runBlocking<Unit> {
        links.forPersona(PERSONA, seed, route)!!.read(byteArrayOf(1))
        links.forPersona(PERSONA, seed.copyOf(), route.copy(card = route.card.copyOf()))!!.advance(byteArrayOf(2))
        assertEquals(1, started.size)
        links.forPersona(PERSONA, seed, route.copy(routeId = "witness-b"))!!.read(byteArrayOf(3))
        assertEquals(2, started.size)
        assertTrue(started[0].stopped)
        links.forPersona(OTHER, seed, route)!!.read(byteArrayOf(4))
        assertEquals(3, started.size)
        assertTrue(!started[1].stopped)
    }

    @Test fun `a request sent while quiet is not sent`() = runBlocking<Unit> {
        val channel = links.forPersona(PERSONA, seed, route)!!
        quiet = true
        assertEquals(WitnessAnswer.Unavailable, channel.read(byteArrayOf(1)))
        assertTrue(started.single().requests.isEmpty())
    }

    @Test fun `pairing runs a throwaway engine on the persona's seed, then the persona's own engine serves the route`() = runBlocking<Unit> {
        val pairing = pairing()
        val secret = pairing.pairingSecret.copyOf()
        val future = links.pair(seed, pairing)
        pairings.single().run()
        val paired = future.get()
        val pairer = started.single()
        assertContentEquals(seed, pairer.seed)
        assertTrue(pairer.routes.isEmpty())
        assertTrue(paired.routeId.startsWith("witness-"))
        assertContentEquals(secret, pairer.paired!!.second)
        // The secret is wiped once used, and the throwaway engine stopped.
        assertContentEquals(ByteArray(16), pairing.pairingSecret)
        assertTrue(pairer.stopped)
        links.forPersona(PERSONA, seed, paired)!!.read(byteArrayOf(1))
        assertEquals(2, started.size)
        assertEquals(listOf(paired.routeId), started[1].routes.map { it.routeId })
    }

    @Test fun `a pairing that never answers blocks no witness traffic`() = runBlocking<Unit> {
        val future = links.pair(seed, pairing())
        // Not run: the box accepted the session and went silent.
        assertEquals(WitnessAnswer.Refused, links.forPersona(PERSONA, seed, route)!!.read(byteArrayOf(1)))
        assertTrue(!future.isDone)
    }

    @Test fun `no pairing while quiet`() {
        quiet = true
        val pairing = pairing()
        val error = assertFailsWith<ExecutionException> { links.pair(seed, pairing).get() }
        assertIs<IllegalStateException>(error.cause)
        assertTrue(pairings.isEmpty())
        assertContentEquals(ByteArray(16), pairing.pairingSecret)
    }

    @Test fun `a Tor-only room opening stops every open session`() = runBlocking<Unit> {
        links.forPersona(PERSONA, seed, route)!!.read(byteArrayOf(1))
        links.forPersona(OTHER, seed, route)!!.read(byteArrayOf(1))
        links.pause()
        assertTrue(started.all { it.stopped })
    }

    @Test fun `an engine stops once idle, but not while it is still in use`() = runBlocking<Unit> {
        links.forPersona(PERSONA, seed, route)!!.read(byteArrayOf(1))
        links.forPersona(PERSONA, seed, route)!!.advance(byteArrayOf(2))
        assertEquals(2, idle.size)
        idle[0]()
        assertTrue(!started.single().stopped)
        idle[1]()
        assertTrue(started.single().stopped)
        // The next request starts it afresh.
        links.forPersona(PERSONA, seed, route)!!.read(byteArrayOf(3))
        assertEquals(2, started.size)
    }

    @Test fun `forgetting a persona stops its engine`() = runBlocking<Unit> {
        links.forPersona(PERSONA, seed, route)!!.read(byteArrayOf(1))
        links.forget(PERSONA)
        assertTrue(started.single().stopped)
    }

    private fun pairing(): BothyPairing {
        val body = buildJsonObject {
            put("v", 2); put("card", Base64.getEncoder().encodeToString(WitnessEnrolmentTest.CARD)); put("bothy", "ab".repeat(32))
            put("secret", "cd".repeat(16)); put("exp", NOW + 600); put("role", "box"); put("name", "witness")
        }.toString().encodeToByteArray()
        return BothyPairing.parse("bothy:" + Base64.getUrlEncoder().withoutPadding().encodeToString(body), NOW)
    }

    private companion object {
        const val NOW = WitnessEnrolmentTest.NOW
        val PERSONA = "ab".repeat(32)
        val OTHER = "cd".repeat(32)
    }
}
