package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.LocalAddressPrivacy
import dev.forgesworn.kithmoot.session.CALL_PROFILE_2
import dev.forgesworn.kithmoot.support.FakePeerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Candidates written into the first description (see [CANDIDATE_GATHER_MS]).
 *
 * The web client's set, against the same fake the rest of the negotiation
 * tests use: what is carried, what still trickles, in what order, and that a
 * replay repeats exactly what was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CandidateGatherTest {

    private val roomId = "room"
    private val low = "aa".repeat(32)
    private val high = "ff".repeat(32)

    private val mdns = "candidate:1 1 udp 2122260223 b1d8e3a7-2f4d-46e9-a105-f4e3d2c1b0a9.local 50000 typ host generation 0"
    private val srflx = "candidate:2 1 udp 1686052607 203.0.113.7 40000 typ srflx raddr 192.168.1.20 rport 54321 generation 0"
    private val relay = "candidate:3 1 udp 41885439 198.51.100.9 3478 typ relay raddr 203.0.113.7 rport 40000 generation 0"
    private val privateHost = "candidate:9 1 udp 2122260223 192.168.1.20 50001 typ host generation 0"

    /** A candidate as it leaves this device when trickled. */
    private fun out(line: String) = LocalAddressPrivacy.candidate(line)!!

    private class Recorder {
        val sent = mutableListOf<SignalEnvelope>()
        suspend fun send(envelope: SignalEnvelope) {
            sent += envelope
        }

        fun types() = sent.map { it.type }
        fun candidates() = sent.filter { it.type == SignalType.ICE }
            .flatMap { it.candidates ?: listOfNotNull(it.candidate) }
            .map { IceCandidateData.fromWire(it)!!.candidate }
    }

    private fun gathering(ufrag: String? = "u1") = FakePeerConnection().apply {
        gathering = IceGathering.GATHERING
        this.ufrag = ufrag
    }

    private fun link(connection: FakePeerConnection, recorder: Recorder) =
        PeerLink(low, high, connection, roomId, recorder::send)

    private fun TestScope.profileTwo(connection: FakePeerConnection, recorder: Recorder) = PeerLink(
        high, low, connection, roomId, recorder::send,
        callProfile = CALL_PROFILE_2,
        scope = backgroundScope,
        newConnectionId = { "1111111111111111" },
        signalRetry = SignalRetryTiming(jitter = 0.0),
    )

    /** The connection found a candidate and raised it, as the native callback does. */
    private suspend fun raise(peer: PeerLink, connection: FakePeerConnection, line: String, written: Boolean = true) {
        if (written) connection.gatherCandidate(line)
        peer.onLocalCandidate(IceCandidateData(line, "0", 0))
    }

    private fun TestScope.windowEnds() {
        advanceTimeBy(CANDIDATE_GATHER_MS)
        runCurrent()
    }

    @Test
    fun `candidates found during the wait are written into the offer and later ones trickle`() = runTest {
        val connection = gathering()
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onNegotiationNeeded() }
        runCurrent()
        raise(peer, connection, mdns)
        raise(peer, connection, srflx)
        assertTrue(recorder.sent.isEmpty(), "the offer waits, and its candidates wait with it")

        windowEnds()
        val offer = recorder.sent.single()
        assertEquals(SignalType.OFFER, offer.type)
        assertTrue("a=$mdns" in offer.sdp!!)
        // Raw in the description: the privacy filter rewrites it on the way
        // out, by the same rule it rewrites a trickled one.
        assertTrue("a=$srflx" in offer.sdp!!)

        raise(peer, connection, relay)
        assertEquals(listOf(SignalType.OFFER, SignalType.ICE), recorder.types())
        assertEquals(listOf(out(relay)), recorder.candidates())
    }

    @Test
    fun `a description that does not accrue candidates is followed by every held one, in order`() = runTest {
        val connection = gathering().apply { accrues = false }
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onNegotiationNeeded() }
        runCurrent()
        raise(peer, connection, mdns)
        raise(peer, connection, relay)
        windowEnds()

        assertEquals(listOf(SignalType.OFFER, SignalType.ICE, SignalType.ICE), recorder.types())
        assertFalse("a=candidate:" in recorder.sent.first().sdp!!)
        assertEquals(listOf(mdns, out(relay)), recorder.candidates())
    }

    @Test
    fun `the wait ends at its timeout`() = runTest {
        val connection = gathering()
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onNegotiationNeeded() }
        runCurrent()
        advanceTimeBy(CANDIDATE_GATHER_MS - 1)
        runCurrent()
        assertTrue(recorder.sent.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(SignalType.OFFER), recorder.types())
    }

    @Test
    fun `gathering completing ends the wait early`() = runTest {
        val connection = gathering()
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onNegotiationNeeded() }
        runCurrent()
        raise(peer, connection, mdns)
        peer.onIceGatheringComplete()
        runCurrent()

        assertEquals(0L, currentTime)
        assertEquals(listOf(SignalType.OFFER), recorder.types())
        assertTrue("a=$mdns" in recorder.sent.single().sdp!!)
    }

    @Test
    fun `the answer carries its candidates and a replayed answer is the identical SDP`() = runTest {
        val connection = gathering().apply { media = setOf("microphone") }
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onRemoteSignal(SignalType.OFFER, sdp = "remote-offer") }
        runCurrent()
        raise(peer, connection, mdns)
        assertTrue(recorder.sent.isEmpty())
        windowEnds()

        val answer = recorder.sent.single()
        assertEquals(SignalType.ANSWER, answer.type)
        assertTrue("a=$mdns" in answer.sdp!!)

        // The far end did not hear it and asks again with the same offer.
        peer.onRemoteSignal(SignalType.OFFER, sdp = "remote-offer")
        assertEquals(1, peer.answersReplayed)
        assertEquals(listOf(SignalType.ANSWER, SignalType.ANSWER), recorder.types())
        assertEquals(answer.sdp, recorder.sent.last().sdp)
    }

    @Test
    fun `a connection that cannot report gathering sends at once`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val peer = link(connection, recorder)

        peer.onNegotiationNeeded()
        assertEquals(listOf(SignalType.OFFER), recorder.types())
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a renegotiation on the same ICE credentials sends at once`() = runTest {
        val connection = gathering()
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onNegotiationNeeded() }
        runCurrent()
        windowEnds()
        peer.onRemoteSignal(SignalType.ANSWER, sdp = "remote-answer")
        val before = currentTime

        // Continual gathering keeps the state at GATHERING for life; a
        // microphone being unmuted must not pay the window for it.
        peer.onNegotiationNeeded()
        assertEquals(listOf(SignalType.OFFER, SignalType.OFFER), recorder.types())
        assertEquals(before, currentTime)
    }

    @Test
    fun `an ICE restart offer waits for its fresh candidates`() = runTest {
        val connection = gathering()
        val recorder = Recorder()
        val peer = profileTwo(connection, recorder)

        launch { peer.openSlots(emptyList(), gen = 1) }
        runCurrent()
        windowEnds()
        peer.onRemoteSignal(
            SignalEnvelope(low, SignalType.ANSWER, roomId, sdp = "remote-answer", gen = 1, conn = "2222222222222222", peerConn = "1111111111111111", seq = 1, re = 1),
        )
        runCurrent()
        val before = recorder.sent.size

        connection.ufrag = "u2"
        launch { peer.restartIce() }
        runCurrent()
        raise(peer, connection, relay)
        assertEquals(before, recorder.sent.size, "the restart offer waits for candidates under its new credentials")
        windowEnds()

        val restart = recorder.sent.drop(before).single { it.type == SignalType.OFFER }
        assertEquals(true, restart.restart)
        assertTrue("a=$relay" in restart.sdp!!)
        assertFalse(out(relay) in recorder.candidates())
    }

    @Test
    fun `close ends the wait and nothing is sent after it`() = runTest {
        val connection = gathering()
        val recorder = Recorder()
        val peer = link(connection, recorder)

        val offering = launch { peer.onNegotiationNeeded() }
        runCurrent()
        raise(peer, connection, mdns)
        peer.close()
        runCurrent()

        assertTrue(offering.isCompleted)
        assertEquals(0L, currentTime)
        assertTrue(recorder.sent.isEmpty(), recorder.sent.toString())
    }

    @Test
    fun `a held candidate the privacy rule removes is never sent`() = runTest {
        val connection = gathering().apply { accrues = false }
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onNegotiationNeeded() }
        runCurrent()
        raise(peer, connection, privateHost)
        raise(peer, connection, mdns)
        windowEnds()

        assertEquals(listOf(SignalType.OFFER, SignalType.ICE), recorder.types())
        assertEquals(listOf(mdns), recorder.candidates())
    }

    @Test
    fun `a candidate reaching the link after the description that already carries it is not trickled`() = runTest {
        val connection = gathering()
        val recorder = Recorder()
        val peer = link(connection, recorder)

        launch { peer.onNegotiationNeeded() }
        runCurrent()
        // Written into the description by the stack, but its callback is
        // scheduled late and only reaches the link once the offer has gone.
        connection.gatherCandidate(mdns)
        windowEnds()
        assertTrue("a=$mdns" in recorder.sent.single().sdp!!)

        peer.onLocalCandidate(IceCandidateData(mdns, "0", 0))
        assertEquals(listOf(SignalType.OFFER), recorder.types())
    }

    @Test
    fun `profile 2 keeps the channel's sequence gapless and retransmits the accrued offer`() = runTest {
        val connection = gathering(ufrag = null)
        val recorder = Recorder()
        val peer = profileTwo(connection, recorder)

        launch { peer.openSlots(emptyList(), gen = 4) }
        runCurrent()
        raise(peer, connection, mdns)
        raise(peer, connection, privateHost)
        raise(peer, connection, relay, written = false)
        windowEnds()

        // The private host candidate is never numbered, so it leaves no hole.
        assertEquals(listOf(SignalType.OFFER, SignalType.ICE), recorder.types())
        assertEquals(listOf(1L, 2L), recorder.sent.map { it.seq })
        assertTrue("a=$mdns" in recorder.sent.first().sdp!!)
        assertEquals(listOf(out(relay)), recorder.candidates())

        raise(peer, connection, srflx)
        assertEquals(3L, recorder.sent.last().seq)

        // Nothing acknowledged: the offer goes out again as what the
        // connection holds now, carrying everything gathered since.
        val first = recorder.sent.size
        advanceTimeBy(30_000)
        runCurrent()
        val again = recorder.sent.drop(first).first { it.type == SignalType.OFFER }
        assertEquals(1L, again.seq)
        assertEquals(connection.localDescription()!!.sdp, again.sdp)
        assertTrue("a=$mdns" in again.sdp!! && "a=$srflx" in again.sdp!!)
    }
}
