package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.support.DescribingPeerConnection
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One-way audio with no fault reported anywhere.
 *
 * The two ends of a pair complete DIFFERENT negotiations and neither can tell.
 * A far end answers an offer before its microphone has reached that connection,
 * so the answer says `recvonly`; the offer is retransmitted; by the time the
 * retransmission is answered the microphone has arrived and the second answer
 * says `sendrecv`. The offerer is already `stable`, so the true answer is
 * refused and thrown away. One side ends up `sendonly` and the other
 * `sendrecv`: RTP arrives and is never played, both connections report
 * `connected`, and both signalling states are `stable`. There is nothing to
 * see.
 *
 * On this client that is a profile-1 shape. A profile-2 pair holds four fixed
 * `sendrecv` slots for the life of the connection and swaps tracks with
 * `setTrack`, so no media change is a negotiation and no direction can move;
 * its reliable channel also deduplicates a retransmitted offer by seq
 * (`SignalChannel.receiveLocked`). Profile 1 has neither: `Negotiation.kt`
 * re-applies and re-answers a repeated offer from scratch, and profile-1
 * signals go straight through with no seq and no deduplication.
 *
 * Every signal here is moved by hand, because which copy of which description
 * arrives in which order IS the bug. Where a test stands a far end in as a bare
 * connection rather than a [PeerLink], it is standing in for an unfixed web
 * client - the pairing this was actually found in.
 */
class NegotiationDisagreementTest {

    private val roomId = "room"

    /** The lower pubkey is polite. Naming them by their role keeps the
     *  sequences below readable. */
    private val politeDevice = "aa".repeat(32)
    private val impoliteDevice = "ff".repeat(32)

    private class Wire {
        val sent = mutableListOf<SignalEnvelope>()

        suspend fun send(envelope: SignalEnvelope) {
            sent += envelope
        }

        fun of(type: String): List<SignalEnvelope> = sent.filter { it.type == type }
        fun last(type: String): SignalEnvelope = of(type).last()
        fun count(type: String): Int = of(type).size
    }

    private fun link(local: String, remote: String, connection: DescribingPeerConnection, wire: Wire) =
        PeerLink(local, remote, connection, roomId, wire::send)

    /**
     * One inbound signal, delivered the way `WebRtcEngine` delivers one.
     *
     * The engine wraps every `onRemoteSignal` in a catch and marks the pair
     * "failed" rather than letting one refused description cancel every other
     * device's collectors. So a description the stack refuses is not merely
     * dropped, it is dropped SILENTLY, which is why the pair can sit split for
     * the rest of the call. Returns whether it was applied.
     */
    private suspend fun deliver(link: PeerLink, body: SignalEnvelope): Boolean =
        try {
            link.onRemoteSignal(body)
            true
        } catch (_: Exception) {
            false
        }

    private fun offer(from: String, sdp: String) = SignalEnvelope(from, SignalType.OFFER, roomId, sdp = sdp)

    private fun answer(from: String, sdp: String) = SignalEnvelope(from, SignalType.ANSWER, roomId, sdp = sdp)

    // -- route 1: this side answers the same offer twice, differently ---------

    @Test
    @Ignore("reproduces the split-direction negotiation; fix pending")
    fun `BUG- a retransmitted offer answered again with a microphone splits the pair`() = runTest {
        // The far end offers, with a microphone. It stands in for an unfixed
        // web client, which retransmits an offer it has had no working media
        // for - re-reading it off the connection, so it goes back out carrying
        // every candidate gathered since.
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val androidConnection = DescribingPeerConnection("and")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, androidConnection, wire)

        val first = far.setLocalDescription()
        // Somebody who has joined but not unmuted yet: profile 1 adds and
        // removes senders, so there is simply no audio track on this
        // connection. `addLocalTrack` has not been called for it.
        assertFalse(androidConnection.hasLocalAudio)
        assertTrue(deliver(android, offer(impoliteDevice, first.sdp)))
        far.setRemoteDescription(SdpData(SignalType.ANSWER, wire.last(SignalType.ANSWER).sdp!!))

        // Both ends agree, for now: the far end sends, this side listens.
        assertEquals("sendonly", far.negotiatedAudio())
        assertEquals("recvonly", androidConnection.negotiatedAudio())

        // And now somebody unmutes. `WebRtcEngine.syncLocalTracks` calls
        // `addLocalTrack`, and the connection has a microphone on it.
        androidConnection.hasLocalAudio = true

        // The far end's retransmission arrives: the same session, more
        // candidates, a bumped session version - different bytes, same shape.
        far.gatherCandidate("candidate:2 1 udp 1694 198.51.100.7 5001 typ srflx")
        val retransmitted = far.localDescription()!!
        assertNotEquals(first.sdp, retransmitted.sdp)
        assertTrue(SdpShape.same(first.sdp, retransmitted.sdp), "the far end re-sent the same session")
        assertTrue(deliver(android, offer(impoliteDevice, retransmitted.sdp)))

        // There is no answer-replay shortcut, so it is answered from scratch -
        // and this time the answer says `sendrecv`.
        val answers = wire.of(SignalType.ANSWER)
        assertEquals(2, answers.size)
        assertFalse(
            SdpShape.same(answers[0].sdp!!, answers[1].sdp!!),
            "the two answers to one offer describe different sessions",
        )

        // The far end is already `stable`. Its own stack refuses the answer.
        assertFalse(far.setRemoteDescriptionQuietly(SdpData(SignalType.ANSWER, answers[1].sdp!!)))

        assertEquals("sendrecv", androidConnection.negotiatedAudio())
        assertEquals(
            androidConnection.negotiatedAudio(),
            far.negotiatedAudio(),
            "both ends of a pair must complete the same negotiation",
        )
    }

    @Test
    @Ignore("reproduces the split-direction negotiation; fix pending")
    fun `BUG- a retransmitted offer answered again after a polite rollback splits the pair`() = runTest {
        // The field sequence exactly: the retransmission collides with this
        // side's own offer, the polite side rolls back, and answers the same
        // offer a second time with a different shape.
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val androidConnection = DescribingPeerConnection("and")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, androidConnection, wire)
        assertTrue(android.polite)

        val first = far.setLocalDescription()
        assertTrue(deliver(android, offer(impoliteDevice, first.sdp)))
        far.setRemoteDescription(SdpData(SignalType.ANSWER, wire.last(SignalType.ANSWER).sdp!!))
        assertEquals("sendonly", far.negotiatedAudio())

        // The microphone arrives and the connection asks to renegotiate. That
        // offer is sent exactly once - profile 1 has no retransmission at all -
        // so a copy lost on the way to the relay is simply gone, and the far
        // end never answers it.
        androidConnection.hasLocalAudio = true
        android.onNegotiationNeeded()
        assertEquals(1, wire.count(SignalType.OFFER))
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, androidConnection.signalingState())

        // The far end's retransmission arrives while our own offer is out.
        far.gatherCandidate("candidate:2 1 udp 1694 198.51.100.7 5001 typ srflx")
        assertTrue(deliver(android, offer(impoliteDevice, far.localDescription()!!.sdp)))

        assertEquals(1, androidConnection.rollbacks, "the polite side gives way")
        assertEquals(1, android.collisionsResolved)

        val answers = wire.of(SignalType.ANSWER)
        assertEquals(2, answers.size)
        assertFalse(SdpShape.same(answers[0].sdp!!, answers[1].sdp!!))

        // And the far end, stable since it applied the first answer, refuses
        // the second - the true one.
        assertFalse(far.setRemoteDescriptionQuietly(SdpData(SignalType.ANSWER, answers[1].sdp!!)))

        assertEquals("sendrecv", androidConnection.negotiatedAudio())
        assertEquals(
            androidConnection.negotiatedAudio(),
            far.negotiatedAudio(),
            "both ends of a pair must complete the same negotiation",
        )
    }

    // -- route 2: this side is handed a second answer it cannot apply --------

    @Test
    @Ignore("reproduces the split-direction negotiation; fix pending")
    fun `BUG- a second answer arriving in stable is dropped and this side stays sendonly`() = runTest {
        // This side offers with a microphone. The far end - an unfixed web
        // client, or any KithMoot Android answerer, which does exactly this -
        // answers once without a microphone and once with.
        val androidConnection = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val far = DescribingPeerConnection("far")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, androidConnection, wire)

        android.onNegotiationNeeded()
        val offered = SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!)

        far.setRemoteDescription(offered)
        val firstAnswer = far.setLocalDescription()
        assertTrue(deliver(android, answer(impoliteDevice, firstAnswer.sdp)))
        assertEquals("sendonly", androidConnection.negotiatedAudio())
        assertEquals("recvonly", far.negotiatedAudio())

        // The far end unmutes and re-applies the offer it still holds, which is
        // what re-answering a retransmitted offer amounts to.
        far.hasLocalAudio = true
        far.setRemoteDescription(offered)
        val secondAnswer = far.setLocalDescription()
        assertFalse(
            SdpShape.same(firstAnswer.sdp, secondAnswer.sdp),
            "the second answer describes a different session",
        )
        assertEquals("sendrecv", far.negotiatedAudio())

        // This side is `stable`. The true answer is refused by the stack and
        // swallowed by the engine, and nothing renegotiates.
        assertFalse(deliver(android, answer(impoliteDevice, secondAnswer.sdp)))
        assertEquals(listOf("answer in STABLE"), androidConnection.refusals)
        assertEquals(1, wire.count(SignalType.OFFER), "nothing noticed, so nothing was repaired")

        assertEquals(
            far.negotiatedAudio(),
            androidConnection.negotiatedAudio(),
            "both ends of a pair must complete the same negotiation",
        )
    }

    // -- guards: what the fix must NOT start doing ---------------------------

    @Test
    fun `a duplicate answer with the same shape but different bytes starts no renegotiation`() = runTest {
        // The ordinary case, and by far the common one: a lost acknowledgement,
        // a relay delivering the same signal twice, an answer re-read off the
        // far end's connection with more candidates on it. Nothing has changed,
        // and a client that renegotiated on every one of these would never stop
        // renegotiating.
        val androidConnection = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, androidConnection, wire)

        android.onNegotiationNeeded()
        far.setRemoteDescription(SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!))
        val first = far.setLocalDescription()
        assertTrue(deliver(android, answer(impoliteDevice, first.sdp)))
        assertEquals("sendrecv", androidConnection.negotiatedAudio())

        far.gatherCandidate("candidate:2 1 udp 1694 198.51.100.7 5001 typ srflx")
        val again = far.localDescription()!!
        assertNotEquals(first.sdp, again.sdp, "the fixture must differ in bytes or it proves nothing")
        assertTrue(SdpShape.same(first.sdp, again.sdp))

        deliver(android, answer(impoliteDevice, again.sdp))

        assertEquals(1, wire.count(SignalType.OFFER), "a duplicate answer is not a disagreement")
        assertEquals("sendrecv", androidConnection.negotiatedAudio())
        assertEquals(androidConnection.negotiatedAudio(), far.negotiatedAudio())
    }

    @Test
    fun `a retransmitted offer answered identically starts no renegotiation`() = runTest {
        // Same guard from the answering seat: nothing about this connection
        // moved between the two answers, so there is nothing to repair.
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val androidConnection = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, androidConnection, wire)

        val first = far.setLocalDescription()
        assertTrue(deliver(android, offer(impoliteDevice, first.sdp)))
        far.setRemoteDescription(SdpData(SignalType.ANSWER, wire.last(SignalType.ANSWER).sdp!!))

        far.gatherCandidate("candidate:2 1 udp 1694 198.51.100.7 5001 typ srflx")
        assertTrue(deliver(android, offer(impoliteDevice, far.localDescription()!!.sdp)))

        val answers = wire.of(SignalType.ANSWER)
        assertEquals(2, answers.size)
        assertTrue(
            SdpShape.same(answers[0].sdp!!, answers[1].sdp!!),
            "the same offer, answered from an unchanged connection, is the same session",
        )
        assertEquals(0, wire.count(SignalType.OFFER), "answering again is not a renegotiation")
        assertEquals("sendrecv", androidConnection.negotiatedAudio())
    }
}

/** [DescribingPeerConnection.setRemoteDescription], refusal and all, as a far
 *  end's own client would experience it: it does not get to throw at us. */
private suspend fun DescribingPeerConnection.setRemoteDescriptionQuietly(sdp: SdpData): Boolean =
    try {
        setRemoteDescription(sdp)
        true
    } catch (_: Exception) {
        false
    }
