package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.support.FakePeerConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Perfect negotiation, tested against a fake peer connection that enforces the
 * same state rules libwebrtc does.
 *
 * Every case here is one two people can produce by accident in the first ten
 * seconds of a call - unmuting together, a candidate overtaking the offer it
 * belongs to - and none of them can be reproduced reliably by putting two
 * handsets on a desk.
 */
class PeerLinkTest {

    private val roomId = "room"
    private val low = "aa".repeat(32)
    private val high = "ff".repeat(32)

    private class Recorder {
        val sent = mutableListOf<SignalEnvelope>()
        suspend fun send(envelope: SignalEnvelope) {
            sent += envelope
        }

        fun types() = sent.map { it.type }
    }

    private fun link(
        local: String,
        remote: String,
        connection: FakePeerConnection,
        recorder: Recorder,
    ) = PeerLink(local, remote, connection, roomId, recorder::send)

    @Test
    fun `politeness is decided by comparing device pubkeys`() {
        val recorder = Recorder()
        assertTrue(link(low, high, FakePeerConnection(), recorder).polite)
        assertFalse(link(high, low, FakePeerConnection(), recorder).polite)
    }

    @Test
    fun `BUG- politeness must still be opposite when a device pubkey reaches PeerLink in a different case on each side`() {
        // Two real devices, X and Y. X's own pubkey is always its own
        // canonical lower-case form. Y's pubkey, as it happens to have
        // reached X's side of the connection (say, decoded off a roster
        // entry Y itself published), is the same identifier but in upper
        // case - nothing on the wire enforces a single case. `hexEquals`
        // would treat these as the same device; the politeness tiebreak
        // uses `<`, which does not.
        //
        // If both sides land on the same politeness because of a case
        // difference like this, both offer at once, neither backs off, and
        // the connection wedges permanently - the exact glare deadlock
        // perfect negotiation exists to prevent. So this must always come
        // out opposite, regardless of case.
        val deviceX = "a".repeat(64)
        val deviceYLower = "b".repeat(64)
        val deviceYAsSeenByX = "B".repeat(64) // the same device, differently cased
        val recorder = Recorder()

        val xSide = link(deviceX, deviceYAsSeenByX, FakePeerConnection(), recorder)
        val ySide = link(deviceYLower, deviceX, FakePeerConnection(), recorder)

        assertEquals(!ySide.polite, xSide.polite)
    }

    @Test
    fun `an ordinary offer and answer completes`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val peer = link(low, high, connection, recorder)

        peer.onNegotiationNeeded()
        assertEquals(listOf(SignalType.OFFER), recorder.types())
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, connection.signalingState())

        peer.onRemoteSignal(SignalType.ANSWER, sdp = "remote-answer", candidate = null)
        assertEquals(SignalingState.STABLE, connection.signalingState())
        assertEquals(0, connection.rollbacks)
    }

    @Test
    fun `an incoming offer is answered`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val peer = link(low, high, connection, recorder)

        peer.onRemoteSignal(SignalType.OFFER, sdp = "remote-offer", candidate = null)

        assertEquals(listOf(SignalType.ANSWER), recorder.types())
        assertEquals(SignalingState.STABLE, connection.signalingState())
        assertEquals(0, connection.rollbacks)
    }

    @Test
    fun `the polite side gives way in a collision`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val peer = link(low, high, connection, recorder)

        // Both sides decided to renegotiate at the same instant. Ours is already
        // in flight when theirs arrives.
        peer.onNegotiationNeeded()
        peer.onRemoteSignal(SignalType.OFFER, sdp = "remote-offer", candidate = null)

        assertEquals(1, connection.rollbacks, "the polite side must roll back to apply their offer")
        assertEquals(1, peer.collisionsResolved)
        assertEquals(listOf(SignalType.OFFER, SignalType.ANSWER), recorder.types())
        assertEquals(SignalingState.STABLE, connection.signalingState())
    }

    @Test
    fun `the impolite side ignores the collision and keeps its own offer`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val peer = link(high, low, connection, recorder)

        peer.onNegotiationNeeded()
        peer.onRemoteSignal(SignalType.OFFER, sdp = "remote-offer", candidate = null)

        assertEquals(0, connection.rollbacks)
        assertEquals(1, peer.offersIgnored)
        assertEquals(listOf(SignalType.OFFER), recorder.types(), "no answer: their offer never happened as far as we are concerned")
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, connection.signalingState())

        // And then the polite side's answer to our offer arrives, and we settle.
        peer.onRemoteSignal(SignalType.ANSWER, sdp = "remote-answer", candidate = null)
        assertEquals(SignalingState.STABLE, connection.signalingState())
    }

    @Test
    fun `two links in glare converge on exactly one offer`() = runTest {
        val politeConnection = FakePeerConnection()
        val impoliteConnection = FakePeerConnection()
        val politeOut = Recorder()
        val impoliteOut = Recorder()
        val polite = link(low, high, politeConnection, politeOut)
        val impolite = link(high, low, impoliteConnection, impoliteOut)

        polite.onNegotiationNeeded()
        impolite.onNegotiationNeeded()

        // Each side's offer crosses the other's on the wire.
        polite.onRemoteSignal(SignalType.OFFER, impoliteOut.sent.last().sdp, null)
        impolite.onRemoteSignal(SignalType.OFFER, politeOut.sent.first().sdp, null)

        // The polite side answered; the impolite side ignored and is still
        // waiting on its own answer.
        impolite.onRemoteSignal(SignalType.ANSWER, politeOut.sent.last().sdp, null)

        assertEquals(SignalingState.STABLE, politeConnection.signalingState())
        assertEquals(SignalingState.STABLE, impoliteConnection.signalingState())
        assertEquals(1, politeConnection.rollbacks)
        assertEquals(0, impoliteConnection.rollbacks)
    }

    @Test
    fun `candidates arriving before the description are buffered, not dropped`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val peer = link(low, high, connection, recorder)

        // Trickle ICE exists so candidates can be sent before gathering
        // finishes, and a candidate routinely overtakes the offer it belongs to.
        // The fake throws on an early addIceCandidate exactly as libwebrtc does,
        // so a client that did not buffer would lose these outright.
        peer.onRemoteSignal(SignalType.ICE, null, "candidate:1 1 udp 1 10.0.0.1 1 typ host")
        peer.onRemoteSignal(SignalType.ICE, null, "candidate:2 1 udp 1 10.0.0.2 2 typ host")
        assertEquals(0, connection.addedCandidates.size)
        assertEquals(2, peer.bufferedCandidateCount)

        peer.onRemoteSignal(SignalType.OFFER, "remote-offer", null)

        assertEquals(2, connection.addedCandidates.size, "buffered candidates must be applied once the description lands")
        assertEquals(
            listOf("candidate:1 1 udp 1 10.0.0.1 1 typ host", "candidate:2 1 udp 1 10.0.0.2 2 typ host"),
            connection.addedCandidates.map { it.candidate },
        )
    }

    @Test
    fun `candidates arriving after the description go straight through`() = runTest {
        val connection = FakePeerConnection()
        val peer = link(low, high, connection, Recorder())

        peer.onRemoteSignal(SignalType.OFFER, "remote-offer", null)
        peer.onRemoteSignal(SignalType.ICE, null, "candidate:3 1 udp 1 10.0.0.3 3 typ host")

        assertEquals(1, connection.addedCandidates.size)
        assertEquals(0, peer.bufferedCandidateCount)
    }

    @Test
    fun `a local candidate is signalled as an ice body`() = runTest {
        val recorder = Recorder()
        val peer = link(low, high, FakePeerConnection(), recorder)

        peer.onLocalCandidate(IceCandidateData("candidate:9 1 udp 1 10.0.0.9 9 typ host"))

        val sent = recorder.sent.single()
        assertEquals(SignalType.ICE, sent.type)
        assertEquals(high, sent.toDevice)
        assertEquals(roomId, sent.roomId)
        assertEquals("candidate:9 1 udp 1 10.0.0.9 9 typ host", sent.candidate)
    }

    @Test
    fun `a candidate the connection refuses does not break the call`() = runTest {
        val connection = FakePeerConnection()
        val peer = link(high, low, connection, Recorder())

        // The impolite side ignored an offer, so candidates belonging to that
        // description are refused. Refusing them is correct; throwing out of the
        // signal handler would tear down the room's subscription.
        peer.onNegotiationNeeded()
        peer.onRemoteSignal(SignalType.OFFER, "remote-offer", null)
        peer.onRemoteSignal(SignalType.ICE, null, "candidate:1 1 udp 1 10.0.0.1 1 typ host")

        assertEquals(SignalingState.HAVE_LOCAL_OFFER, connection.signalingState())
    }

    @Test
    fun `unknown signal types are ignored`() = runTest {
        val connection = FakePeerConnection()
        val peer = link(low, high, connection, Recorder())

        peer.onRemoteSignal("something-new", "sdp", null)

        assertEquals(SignalingState.STABLE, connection.signalingState())
    }

    @Test
    fun `closing releases the connection and the buffer`() = runTest {
        val connection = FakePeerConnection()
        val peer = link(low, high, connection, Recorder())
        peer.onRemoteSignal(SignalType.ICE, null, "candidate:1 1 udp 1 10.0.0.1 1 typ host")

        peer.close()

        assertTrue(connection.closed)
    }
}
