package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.support.DescribingPeerConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A far end that rebuilt its connection and offers from the new one.
 *
 * Seen on a real call: a web peer whose connection to the phone had failed
 * opened a new `RTCPeerConnection` and offered from it. The phone applied the
 * offer to its OLD connection, libwebrtc refused it ("The order of m-lines in
 * subsequent offer doesn't match order from previous offer/answer"), and every
 * retransmission was refused the same way. The phone never answered, so it
 * never heard that person, while that person still heard the phone.
 *
 * The engine owns the factory, so the tests stand in for it: [Engine] swaps a
 * link for a fresh one on a fresh connection exactly as
 * `WebRtcEngine.replaceForRemoteSession` does, and every signal after that goes
 * to whichever link is current.
 */
class RemoteSessionRestartTest {

    private val roomId = "room"
    private val politeDevice = "aa".repeat(32)
    private val impoliteDevice = "ff".repeat(32)

    private class Wire {
        val sent = mutableListOf<SignalEnvelope>()
        var label: String? = null

        suspend fun send(envelope: SignalEnvelope) {
            sent += envelope
        }

        fun of(type: String): List<SignalEnvelope> = sent.filter { it.type == type }
        fun count(type: String): Int = of(type).size
    }

    /** The part of `WebRtcEngine` this needs: one current link per device, and
     *  a replacement on demand. */
    private inner class Engine(
        private val local: String,
        private val remote: String,
        val wire: Wire,
    ) {
        val connections = mutableListOf<DescribingPeerConnection>()
        var current: PeerLink = open()
            private set

        private fun open(): PeerLink {
            val connection = DescribingPeerConnection("and${connections.size}").apply { hasLocalAudio = true }
            connections += connection
            lateinit var link: PeerLink
            link = PeerLink(
                local,
                remote,
                connection,
                roomId,
                wire::send,
                onRemoteRestart = { offer, candidates ->
                    if (current === link) {
                        val fresh = open()
                        current = fresh
                        link.close()
                        fresh.onRemoteSignal(offer)
                        for (candidate in candidates) {
                            fresh.onRemoteSignal(SignalEnvelope(remote, SignalType.ICE, roomId, candidate = candidate.toWire()))
                        }
                    }
                },
            )
            return link
        }

        /** One inbound signal, caught the way the engine's collector catches it. */
        suspend fun deliver(body: SignalEnvelope) {
            try {
                current.onRemoteSignal(body)
            } catch (_: Exception) {
                wire.label = "failed"
            }
        }
    }

    private fun offer(sdp: String) = SignalEnvelope(impoliteDevice, SignalType.OFFER, roomId, sdp = sdp)
    private fun answer(sdp: String) = SignalEnvelope(impoliteDevice, SignalType.ANSWER, roomId, sdp = sdp)

    /** A far end, with its connection negotiated with this device's. */
    private suspend fun negotiated(engine: Engine, far: DescribingPeerConnection) {
        engine.deliver(offer(far.setLocalDescription().sdp))
        far.setRemoteDescription(SdpData(SignalType.ANSWER, engine.wire.of(SignalType.ANSWER).last().sdp!!))
    }

    @Test
    fun `BUG- without a replacement every copy of the new session's offer is refused`() = runTest {
        val wire = Wire()
        val here = DescribingPeerConnection("and")
        val android = PeerLink(politeDevice, impoliteDevice, here, roomId, wire::send)
        val before = DescribingPeerConnection("web", sessionId = "1111")
        android.onRemoteSignal(offer(before.setLocalDescription().sdp))

        val after = DescribingPeerConnection("web", sessionId = "2222").apply { restartIce() }
        val rebuilt = after.setLocalDescription().sdp
        repeat(3) {
            try {
                android.onRemoteSignal(offer(rebuilt))
            } catch (_: Exception) {
                wire.label = "failed"
            }
        }

        // What the phone's logcat said: one answer, to the first session, and
        // then nothing but refusals.
        assertEquals(1, wire.count(SignalType.ANSWER))
        assertEquals(3, here.refusals.size)
        assertEquals("failed", wire.label)
    }

    @Test
    fun `an offer from a new far-end session is answered on a fresh connection`() = runTest {
        val engine = Engine(politeDevice, impoliteDevice, Wire())
        val old = DescribingPeerConnection("web", sessionId = "1111").apply { hasLocalAudio = true }
        negotiated(engine, old)
        val stale = engine.current
        assertEquals(1, engine.wire.count(SignalType.ANSWER))

        // The far end's connection failed; it built another and offers from it.
        val rebuilt = DescribingPeerConnection("web", sessionId = "2222").apply {
            hasLocalAudio = true
            restartIce()
        }
        engine.deliver(offer(rebuilt.setLocalDescription().sdp))

        assertNull(engine.wire.label, "nothing failed")
        assertTrue(engine.current !== stale, "the link was replaced")
        assertEquals(2, engine.connections.size)
        assertTrue(engine.connections[0].closed, "the old connection is gone")
        // Nothing was even tried on the old connection: the new session was
        // seen before anything was applied.
        assertTrue(engine.connections[0].refusals.isEmpty())
        assertEquals(1, stale.remoteRestarts)

        // Answered from the new connection, and the far end can apply it.
        assertEquals(2, engine.wire.count(SignalType.ANSWER))
        rebuilt.setRemoteDescription(SdpData(SignalType.ANSWER, engine.wire.of(SignalType.ANSWER).last().sdp!!))
        assertEquals("sendrecv", rebuilt.negotiatedAudio())
        assertEquals("sendrecv", engine.connections[1].negotiatedAudio())
    }

    @Test
    fun `retransmissions of the new session's offer replace nothing and offer nothing`() = runTest {
        val engine = Engine(politeDevice, impoliteDevice, Wire())
        negotiated(engine, DescribingPeerConnection("web", sessionId = "1111").apply { hasLocalAudio = true })

        val rebuilt = DescribingPeerConnection("web", sessionId = "2222").apply {
            hasLocalAudio = true
            restartIce()
        }
        val first = rebuilt.setLocalDescription().sdp
        engine.deliver(offer(first))
        val replacement = engine.current

        // Twelve more copies, as the web client's retry sends them - re-read
        // off its connection, so later ones carry candidates.
        repeat(12) { copy ->
            if (copy == 4) rebuilt.gatherCandidate("candidate:1 1 udp 2122 192.0.2.4 5000 typ host")
            engine.deliver(offer(rebuilt.localDescription()!!.sdp))
        }

        assertTrue(engine.current === replacement, "exactly one replacement")
        assertEquals(2, engine.connections.size)
        assertEquals(0, replacement.remoteRestarts)
        // Every copy answered, from store, and not one offer from this side.
        assertEquals(1 + 13, engine.wire.count(SignalType.ANSWER))
        assertEquals(12, replacement.answersReplayed)
        assertEquals(0, engine.wire.count(SignalType.OFFER))
        assertNull(engine.wire.label)
    }

    @Test
    fun `the impolite side does not ignore a new session's offer as glare`() = runTest {
        // This side is impolite and has an offer of its own out, to a
        // connection the far end has since thrown away. Treating the new
        // session's offer as a collision would wait for ever on an answer
        // nobody can send.
        val engine = Engine(impoliteDevice, politeDevice, Wire())
        negotiated(engine, DescribingPeerConnection("web", sessionId = "1111").apply { hasLocalAudio = true })
        engine.current.onNegotiationNeeded()
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, engine.connections[0].signalingState())

        val rebuilt = DescribingPeerConnection("web", sessionId = "2222").apply {
            hasLocalAudio = true
            restartIce()
        }
        engine.deliver(offer(rebuilt.setLocalDescription().sdp))

        assertEquals(2, engine.connections.size)
        assertEquals(0, engine.connections[0].rollbacks)
        assertEquals(2, engine.wire.count(SignalType.ANSWER))
        assertNull(engine.wire.label)
    }

    @Test
    fun `the stack's refusal hands the offer on when the comparison misses it`() = runTest {
        // A new session id with the same credentials is not enough to be
        // called a new session in advance - so the stack is left to refuse it,
        // and its words are the evidence.
        val engine = Engine(politeDevice, impoliteDevice, Wire())
        negotiated(engine, DescribingPeerConnection("web", sessionId = "1111").apply { hasLocalAudio = true })

        val rebuilt = DescribingPeerConnection("web", sessionId = "2222").apply { hasLocalAudio = true }
        val sdp = rebuilt.setLocalDescription().sdp
        engine.deliver(offer(sdp))

        assertEquals(1, engine.connections[0].refusals.size, "tried on the old connection first")
        assertEquals(2, engine.connections.size)
        assertEquals(2, engine.wire.count(SignalType.ANSWER))
        assertNull(engine.wire.label)

        // And once only: the next copy is the replacement's to answer.
        engine.deliver(offer(sdp))
        assertEquals(2, engine.connections.size)
        assertEquals(3, engine.wire.count(SignalType.ANSWER))
    }

    @Test
    fun `an ICE restart on the same far-end connection is not a new session`() = runTest {
        val engine = Engine(politeDevice, impoliteDevice, Wire())
        val far = DescribingPeerConnection("web", sessionId = "1111").apply { hasLocalAudio = true }
        negotiated(engine, far)

        far.restartIce()
        engine.deliver(offer(far.setLocalDescription().sdp))

        assertEquals(1, engine.connections.size, "the connection that exists takes a restart")
        assertEquals(2, engine.wire.count(SignalType.ANSWER))
        assertNull(engine.wire.label)
    }

    @Test
    fun `candidates that overtook the new session's offer go with it`() = runTest {
        val engine = Engine(politeDevice, impoliteDevice, Wire())
        negotiated(engine, DescribingPeerConnection("web", sessionId = "1111").apply { hasLocalAudio = true })

        val rebuilt = DescribingPeerConnection("web", sessionId = "2222").apply {
            hasLocalAudio = true
            restartIce()
        }
        val sdp = rebuilt.setLocalDescription().sdp
        val ufrag = assertNotNull(SdpSession.of(sdp).ufrag)
        val early = IceCandidateData("candidate:1 1 udp 2122 192.0.2.4 5000 typ host", "0", 0, ufrag)
        engine.deliver(SignalEnvelope(impoliteDevice, SignalType.ICE, roomId, candidate = early.toWire()))
        // Held, not refused against the old session.
        assertTrue(engine.connections[0].addedCandidates.isEmpty())

        engine.deliver(offer(sdp))

        assertEquals(listOf(early.candidate), engine.connections[1].addedCandidates.map { it.candidate })
        assertTrue(engine.connections[0].addedCandidates.isEmpty())
    }

    @Test
    fun `a profile-1 link with nobody to hand on to behaves as it always did`() = runTest {
        val wire = Wire()
        val here = DescribingPeerConnection("and")
        val android = PeerLink(politeDevice, impoliteDevice, here, roomId, wire::send)
        android.onRemoteSignal(offer(DescribingPeerConnection("web", sessionId = "1111").setLocalDescription().sdp))
        val rebuilt = DescribingPeerConnection("web", sessionId = "2222").apply { restartIce() }
        val refused = runCatching { android.onRemoteSignal(offer(rebuilt.setLocalDescription().sdp)) }
        assertTrue(refused.isFailure)
        assertEquals(0, android.remoteRestarts)
        assertFalse(here.closed)
    }

    @Test
    fun `session and credentials are read off the description`() {
        val sdp = "v=0\r\no=- 8123 2 IN IP4 127.0.0.1\r\ns=-\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ice-ufrag:abcd\r\n"
        assertEquals(SdpSession("8123", "abcd"), SdpSession.of(sdp))
        assertTrue(SdpSession("2", "b").replaces(SdpSession("1", "a")))
        assertFalse(SdpSession("1", "b").replaces(SdpSession("1", "a")), "an ICE restart")
        assertFalse(SdpSession("2", "a").replaces(SdpSession("1", "a")))
        assertFalse(SdpSession(null, "b").replaces(SdpSession("1", "a")))
        assertTrue(isSessionMismatch(IllegalStateException(
            "Failed to set remote offer sdp: The order of m-lines in subsequent offer doesn't match order from previous offer/answer.",
        )))
        assertFalse(isSessionMismatch(IllegalStateException("Called in wrong state: have-local-offer")))
    }
}
