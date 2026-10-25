package dev.forgesworn.kithmoot.media

import org.webrtc.PeerConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallIceSelectorTest {
    private val hand = CallRelayConfig.parse("turn:own.example.org:3478", "me", "own-secret")!!
    private val spry = CallRelayConfig.parse("turn:spry.example.org:3478", "spry", "spry-secret")!!
    private val spry2 = CallRelayConfig.parse("turn:spry.example.org:3478", "spry", "renewed-secret")!!
    private val stun = CallStunConfig.parse("stun:stun.own.example.org:3478")!!
    private val found = PinnedPublicRelay("a".repeat(64), "relay.example.org",
        listOf("turn:203.0.114.7:3478?transport=udp", "turns:203.0.114.7:5349?transport=tcp"), "1793003600:pub-0123456789abcdef01234567", "c2VjcmV0", 1_793_003_600)

    private class Engine { val pushes = ArrayList<List<PeerConnection.IceServer>>() }
    private fun selector(hand: CallRelayConfig? = null, relayOnly: Boolean = false, own: CallStunConfig? = null): Pair<CallIceSelector, Engine> {
        val engine = Engine()
        val selector = CallIceSelector(hand, relayOnly, own, defaults = listOf("stun:default.example.org:3478"))
        return selector to engine
    }
    private fun CallIceSelector.built(engine: Engine, discoveredChosen: Boolean = false): List<PeerConnection.IceServer>? =
        initial(discoveredChosen).also { attach { engine.pushes += it } }
    private fun turnUrls(servers: List<PeerConnection.IceServer>?) = servers.orEmpty().filter { s -> s.urls.any { it.startsWith("turn") } }.flatMap { it.urls }
    private fun passwords(servers: List<PeerConnection.IceServer>?) = servers.orEmpty().map { it.password }.filter { it.isNotEmpty() }

    @Test fun precedenceForEveryCombination() {
        for (relayOnly in listOf(false, true)) for (withHand in listOf(false, true)) for (withSpry in listOf(false, true)) for (withFound in listOf(false, true)) {
            val label = "relayOnly=$relayOnly hand=$withHand spry=$withSpry found=$withFound"
            val (selector, engine) = selector(if (withHand) hand else null, relayOnly)
            if (withSpry) selector.updateSpry(spry)
            selector.built(engine, discoveredChosen = withFound)
            val accepted = selector.updateDiscovered(if (withFound) found else null)
            val expected = when {
                withHand -> CallRelaySource.HAND_ENTERED
                withSpry -> CallRelaySource.SPRY
                withFound -> CallRelaySource.DISCOVERED
                else -> CallRelaySource.NONE
            }
            assertEquals(expected, selector.source, label)
            if (withFound) assertEquals(!withHand && !withSpry, accepted, label)
            val now = selector.current()
            when (expected) {
                CallRelaySource.HAND_ENTERED -> assertEquals(listOf("own-secret"), passwords(now.servers), label)
                CallRelaySource.SPRY -> assertEquals(listOf("spry-secret"), passwords(now.servers), label)
                CallRelaySource.DISCOVERED -> {
                    assertEquals(found.urls, turnUrls(now.servers), label)
                    assertEquals("relay.example.org", now.servers!!.last().hostname, label)
                }
                CallRelaySource.NONE -> if (relayOnly) assertNull(now.servers, label) else assertFalse(now.hasRelay, label)
            }
            if (relayOnly) assertTrue(now.servers == null || now.servers.all { s -> s.urls.all { it.startsWith("turn") } }, label)
        }
    }

    @Test fun spryArrivingMidCallNeverOverridesAHandEnteredRelay() {
        val (selector, engine) = selector(hand)
        val built = selector.built(engine)
        assertEquals(listOf("own-secret"), passwords(built))
        selector.updateSpry(spry)
        selector.updateSpry(null)
        selector.updateSpry(spry2)
        assertTrue(engine.pushes.isEmpty())
        assertEquals(CallRelaySource.HAND_ENTERED, selector.source)
    }

    @Test fun spryExpiringInRelayOnlyNeverPushesAnEmptyList() {
        val (selector, engine) = selector(relayOnly = true)
        selector.updateSpry(spry)
        val relays = ArrayList<Boolean>()
        selector.onRelayChanged = { relays += it }
        assertEquals(listOf("spry-secret"), passwords(selector.built(engine)))
        selector.updateSpry(null)
        assertTrue(engine.pushes.isEmpty())
        assertEquals(false, relays.last())
        selector.updateSpry(spry2)
        assertEquals(listOf(listOf("renewed-secret")), engine.pushes.map(::passwords))
        assertTrue(engine.pushes.none { it.isEmpty() })
    }

    @Test fun spryExpiringInAnOrdinaryCallKeepsThePersonsOwnStun() {
        val (selector, engine) = selector(own = stun)
        selector.updateSpry(spry)
        selector.built(engine)
        selector.updateSpry(null)
        assertEquals(listOf(listOf("stun:stun.own.example.org:3478")), engine.pushes.map { pushed -> pushed.flatMap { it.urls } })
    }

    @Test fun spryRenewalsReachTheEngineWithTheOwnStunKept() {
        val (selector, engine) = selector(own = stun)
        selector.updateSpry(spry)
        selector.built(engine)
        selector.updateSpry(spry2)
        assertEquals(1, engine.pushes.size)
        assertEquals("stun:stun.own.example.org:3478", engine.pushes.single().first().urls.single())
        assertEquals("renewed-secret", engine.pushes.single().last().password)
    }

    @Test fun aSpryCredentialThatLandsBeforeTheEngineIsPushedOnAttach() {
        val (selector, engine) = selector()
        val built = selector.initial(false)
        assertFalse(CallIceServers.hasRelay(built!!))
        selector.updateSpry(spry)
        selector.attach { engine.pushes += it }
        assertEquals(listOf(listOf("spry-secret")), engine.pushes.map(::passwords))
    }

    @Test fun aDiscoveredRelayStandsDownWhenSpryArrivesAndDoesNotReturn() {
        val (selector, engine) = selector()
        var superseded = 0
        selector.onDiscoveredSuperseded = { superseded++ }
        selector.built(engine)
        assertTrue(selector.discoveredMayStart())
        assertTrue(selector.updateDiscovered(found))
        assertEquals(CallRelaySource.DISCOVERED, selector.source)
        selector.updateSpry(spry)
        assertEquals(1, superseded)
        assertEquals(CallRelaySource.SPRY, selector.source)
        assertFalse(selector.discoveredMayStart())
        // Spry lapsing does not bring the discovered relay back in this call.
        selector.updateSpry(null)
        assertFalse(selector.updateDiscovered(found))
        assertEquals(CallRelaySource.NONE, selector.source)
        assertEquals(listOf(true, true, false), engine.pushes.map(CallIceServers::hasRelay))
        // A new call with no Spry relay may use one again.
        selector.callEnded()
        assertTrue(selector.discoveredMayStart())
    }

    @Test fun noDiscoveredRelayWhileAHandEnteredOrSpryRelayIsPresent() {
        assertFalse(selector(hand).first.discoveredMayStart())
        val (withSpry, _) = selector()
        withSpry.updateSpry(spry)
        assertFalse(withSpry.discoveredMayStart())
        assertFalse(withSpry.updateDiscovered(found))
    }

    @Test fun relayOnlyIsBuiltEmptyOnlyForAChosenDiscoveredRelay() {
        assertNull(selector(relayOnly = true).first.initial(discoveredChosen = false))
        assertEquals(emptyList(), selector(relayOnly = true).first.initial(discoveredChosen = true))
        // A hand-entered relay builds with it, whatever was chosen.
        assertEquals(listOf("own-secret"), passwords(selector(hand, relayOnly = true).first.initial(discoveredChosen = true)))
    }

    @Test fun relayOnlyDiscoveredRelayCarriesNoStunAndOnlyIpUrls() {
        val (selector, engine) = selector(relayOnly = true)
        selector.built(engine, discoveredChosen = true)
        selector.updateDiscovered(found)
        val pushed = engine.pushes.single()
        assertEquals(1, pushed.size)
        assertEquals(found.urls, pushed.single().urls)
        assertEquals("relay.example.org", pushed.single().hostname)
        // Lost: nothing empty is pushed over the call.
        selector.updateDiscovered(null)
        assertEquals(1, engine.pushes.size)
    }

    @Test fun anOrdinaryCallGoesBackToStunWhenTheDiscoveredRelayEnds() {
        val (selector, engine) = selector()
        selector.built(engine)
        selector.updateDiscovered(found)
        selector.callEnded()
        assertEquals(listOf(true, false), engine.pushes.map(CallIceServers::hasRelay))
        assertEquals(listOf("stun:default.example.org:3478"), engine.pushes.last().flatMap { it.urls })
    }

    @Test fun aDiscoveredRelaysStunIsItsPinnedAddress() {
        val servers = CallIceServers.resolveDiscovered(found, null, relayOnly = false)
        assertEquals(listOf("stun:203.0.114.7:3478"), servers.first().urls)
        assertEquals(found.urls, servers.last().urls)
        assertEquals(found.username, servers.last().username)
        val own = CallIceServers.resolveDiscovered(found, stun, relayOnly = false)
        assertEquals(listOf("stun:stun.own.example.org:3478"), own.first().urls)
    }

    @Test fun nothingIsPushedAfterDetach() {
        val (selector, engine) = selector()
        selector.built(engine)
        selector.detach()
        selector.updateSpry(spry)
        assertTrue(engine.pushes.isEmpty())
    }
}
