package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.webrtc.PeerConnection
import java.net.InetAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PublicRelayCallRouteTest {
    /** Background work runs on time steps, not on advanceUntilIdle. */
    private fun TestScope.settle() { advanceTimeBy(1000); runCurrent() }

    private class Harness(val scope: TestScope, relayOnly: Boolean = false, builtForDiscovered: Boolean = relayOnly) {
        val io = StandardTestDispatcher(scope.testScheduler)
        val clock = { Adverts.BASE + 60 + scope.testScheduler.currentTime / 1000 }
        var fetches = 0
        var answer: () -> PublicRelayLoginResult? = { null }
        val relays = PublicCallRelays(PublicCallRelayStore(MemoryStorage()), { advert ->
            fetches++
            answer() ?: Adverts.issued(advert, clock() + 3600)
        }, clock, io)
        val selector = CallIceSelector(null, relayOnly, null, defaults = listOf("stun:default.example.org:3478"))
        val pushes = ArrayList<List<PeerConnection.IceServer>>()
        var onCall = false
        var may = true
        var gate: CompletableDeferred<Unit>? = null
        val notices = ArrayList<PublicRelayNotice>()
        var refused = 0
        var ended = 0
        val route = PublicRelayCallRoute(selector, relays, scope.backgroundScope, { emptyList() },
            mayRun = { gate?.await(); may },
            pin = { login -> PublicRelayPinning.pin(login) { listOf(InetAddress.getByName("8.8.4.4")) } },
            live = { true }, onCall = { onCall }, notice = { notices += it },
            refuse = { refused++; onCall = false },
            builtForDiscovered = builtForDiscovered,
            callEnded = { ended++; relays.dropLogins(); relays.newBudget() },
            clock = clock, random = Random(1))

        init { selector.initial(builtForDiscovered); selector.attach { pushes += it } }

        suspend fun ready(): String {
            relays.setEnabled(true)
            val key = relays.discover { listOf(Adverts.signed(1, at = clock() - 60)) }.single().key
            relays.select(key)
            return key
        }
        fun join() { onCall = true; route.onCallChanged(true) }
        fun leave() { onCall = false; route.onCallChanged(false) }
    }

    @Test fun aRoomOpeningOffACallEndsNothingAndKeepsTheChecksLogin() = runTest {
        val h = Harness(this)
        val key = h.ready()
        assertTrue(h.relays.login(key) is PublicRelayLoginResult.Issued)
        h.route.onCallChanged(false)
        h.route.onCallChanged(false)
        assertEquals(0, h.ended)
        // The check's login is still held: joining uses it without a fetch.
        h.join(); settle()
        assertEquals(1, h.fetches)
        assertEquals(CallRelaySource.DISCOVERED, h.selector.source)
        h.leave()
        assertEquals(1, h.ended)
    }

    @Test fun twoJoinSignalsStartOneCallAndNothingRenewsAfterLeaving() = runTest {
        val h = Harness(this)
        h.ready()
        h.gate = CompletableDeferred()
        h.join()
        // A flicker while the first attempt waits: off and on again.
        h.leave()
        h.join()
        runCurrent()
        h.gate!!.complete(Unit)
        settle()
        assertEquals(1, h.fetches)
        h.leave()
        assertEquals(CallRelaySource.NONE, h.selector.source)
        advanceTimeBy(4 * 3600 * 1000L)
        runCurrent()
        assertEquals(1, h.fetches)
        assertTrue(h.notices.isEmpty())
    }

    @Test fun aLeaveDuringTheLoginLeavesNoRelayApplied() = runTest {
        val h = Harness(this)
        h.ready()
        h.gate = CompletableDeferred()
        h.join()
        runCurrent()
        h.leave()
        h.gate!!.complete(Unit)
        settle()
        assertEquals(0, h.fetches)
        assertEquals(CallRelaySource.NONE, h.selector.source)
        assertTrue(h.pushes.none(CallIceServers::hasRelay))
    }

    @Test fun relayOnlyRefusalAlwaysSaysCallRelayLost() = runTest {
        // Nothing chosen any more (switched off after the engine was built).
        val off = Harness(this, relayOnly = true)
        off.may = false
        off.join(); settle()
        assertEquals(1, off.refused)
        assertEquals(listOf(PublicRelayNotice.START_FAILED_RELAY_ONLY), off.notices)
        // The operator refused: said once, not twice.
        val failed = Harness(this, relayOnly = true)
        failed.ready()
        failed.answer = { PublicRelayLoginResult.Failed }
        failed.join(); settle()
        assertEquals(1, failed.refused)
        assertEquals(listOf(PublicRelayNotice.START_FAILED_RELAY_ONLY), failed.notices)
        assertTrue(failed.notices.single().text.startsWith("Call relay lost"))
    }

    @Test fun relayOnlyCallEndPushesAnEmptyList() = runTest {
        val h = Harness(this, relayOnly = true)
        h.ready()
        h.join(); settle()
        assertTrue(CallIceServers.hasRelay(h.pushes.last()))
        h.leave()
        assertEquals(emptyList(), h.pushes.last())
    }

    @Test fun anOrdinaryCallThatCannotUseItsRelayCarriesOnWithoutRefusing() = runTest {
        val h = Harness(this)
        h.ready()
        h.answer = { PublicRelayLoginResult.Failed }
        h.join(); settle()
        assertEquals(0, h.refused)
        assertEquals(listOf(PublicRelayNotice.START_FAILED), h.notices)
        assertFalse(CallIceServers.hasRelay(h.selector.current().servers!!))
    }
}
