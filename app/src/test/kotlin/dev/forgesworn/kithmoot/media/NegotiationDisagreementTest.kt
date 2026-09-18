package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.support.DescribingPeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
 * its reliable channel also deduplicates a retransmitted offer by seq. Profile
 * 1 has neither, and its offers are sent exactly once with nothing to ask for
 * them again.
 *
 * Every signal here is moved by hand, because which copy of which description
 * arrives in which order IS the bug.
 */
class NegotiationDisagreementTest {

    private val roomId = "room"

    /** The lower pubkey is polite. Naming them by their role keeps the
     *  sequences below readable. */
    private val politeDevice = "aa".repeat(32)
    private val impoliteDevice = "ff".repeat(32)

    private val candidate = "candidate:2 1 udp 1694 198.51.100.7 5001 typ srflx"

    private class Wire {
        val sent = mutableListOf<SignalEnvelope>()

        /**
         * What `WebRtcEngine` would have put in its connection state map.
         *
         * Null unless a signal threw: the engine catches everything out of
         * `onRemoteSignal` and marks the pair "failed", which on profile 1 tears
         * nothing down and stops no media - it only relabels the tile, and the
         * label is sticky, because nothing rewrites it until the transport's
         * own state changes again. A settled connection's state does not change
         * again, so a working pair can read "Video connection failed" for the
         * rest of the call.
         */
        var label: String? = null

        suspend fun send(envelope: SignalEnvelope) {
            sent += envelope
        }

        fun of(type: String): List<SignalEnvelope> = sent.filter { it.type == type }
        fun last(type: String): SignalEnvelope = of(type).last()
        fun count(type: String): Int = of(type).size
    }

    private fun link(
        local: String,
        remote: String,
        connection: DescribingPeerConnection,
        wire: Wire,
        scope: CoroutineScope? = null,
    ) = PeerLink(local, remote, connection, roomId, wire::send, scope = scope)

    /** One inbound signal, delivered the way `WebRtcEngine` delivers one. */
    private suspend fun deliver(link: PeerLink, wire: Wire, body: SignalEnvelope) {
        try {
            link.onRemoteSignal(body)
        } catch (_: Exception) {
            wire.label = "failed"
        }
    }

    private fun offer(sdp: String) = SignalEnvelope(impoliteDevice, SignalType.OFFER, roomId, sdp = sdp)

    private fun answer(sdp: String) = SignalEnvelope(impoliteDevice, SignalType.ANSWER, roomId, sdp = sdp)

    // -- route 1: this side answers the same offer twice, differently ---------

    @Test
    fun `BUG- a retransmitted offer answered again with a microphone splits the pair`() = runTest {
        // The far end offers, with a microphone, and retransmits an offer it
        // has had no working media for - re-read off its connection, so it goes
        // back out carrying every candidate gathered since.
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val here = DescribingPeerConnection("and")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)

        val first = far.setLocalDescription()
        // Somebody who has joined but not unmuted yet: profile 1 adds and
        // removes senders, so there is simply no audio track on this
        // connection. `addLocalTrack` has not been called for it.
        assertFalse(here.hasLocalAudio)
        deliver(android, wire, offer(first.sdp))
        far.setRemoteDescription(SdpData(SignalType.ANSWER, wire.last(SignalType.ANSWER).sdp!!))

        // Both ends agree, for now: the far end sends, this side listens.
        assertEquals("sendonly", far.negotiatedAudio())
        assertEquals("recvonly", here.negotiatedAudio())

        // And now somebody unmutes. `WebRtcEngine.syncLocalTracks` calls
        // `addLocalTrack`, and the connection has a microphone on it.
        here.hasLocalAudio = true

        // The far end's retransmission arrives: the same session, more
        // candidates, a bumped session version - different bytes, same shape.
        far.gatherCandidate(candidate)
        val retransmitted = far.localDescription()!!
        assertNotEquals(first.sdp, retransmitted.sdp)
        assertTrue(SdpShape.same(first.sdp, retransmitted.sdp), "the far end re-sent the same session")
        deliver(android, wire, offer(retransmitted.sdp))

        // Answered afresh, because local media moved - and the new answer says
        // something the old one did not.
        val answers = wire.of(SignalType.ANSWER)
        assertEquals(2, answers.size)
        assertFalse(SdpShape.same(answers[0].sdp!!, answers[1].sdp!!))

        // The far end is already `stable`, so it refuses the second answer,
        // exactly as an unfixed client does.
        assertFalse(far.applies(SdpData(SignalType.ANSWER, answers[1].sdp!!)))

        // Which is why this side does not leave it there: one ordinary
        // renegotiation, which the far end can always apply.
        assertEquals(1, android.disagreementsRepaired)
        val repair = wire.last(SignalType.OFFER)
        far.setRemoteDescription(SdpData(SignalType.OFFER, repair.sdp!!))
        deliver(android, wire, answer(far.setLocalDescription().sdp))

        assertEquals("sendrecv", here.negotiatedAudio())
        assertEquals(here.negotiatedAudio(), far.negotiatedAudio(), "both ends complete the same negotiation")
        assertNull(wire.label)
    }

    @Test
    fun `BUG- a retransmitted offer answered again after a polite rollback splits the pair`() = runTest {
        // The field sequence exactly: the retransmission collides with this
        // side's own offer, the polite side rolls back, and answers the same
        // offer a second time with a different shape.
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val here = DescribingPeerConnection("and")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)
        assertTrue(android.polite)

        val first = far.setLocalDescription()
        deliver(android, wire, offer(first.sdp))
        far.setRemoteDescription(SdpData(SignalType.ANSWER, wire.last(SignalType.ANSWER).sdp!!))
        assertEquals("sendonly", far.negotiatedAudio())

        // The microphone arrives and the connection asks to renegotiate. That
        // offer is sent exactly once - profile 1 has no retransmission at all -
        // so a copy lost on the way to the relay is simply gone, and the far
        // end never answers it.
        here.hasLocalAudio = true
        android.onNegotiationNeeded()
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, here.signalingState())

        // The far end's retransmission arrives while our own offer is out.
        far.gatherCandidate(candidate)
        deliver(android, wire, offer(far.localDescription()!!.sdp))

        assertEquals(1, here.rollbacks, "the polite side gives way")
        assertEquals(1, android.collisionsResolved)

        val answers = wire.of(SignalType.ANSWER)
        assertEquals(2, answers.size)
        assertFalse(SdpShape.same(answers[0].sdp!!, answers[1].sdp!!))

        // The far end, stable since it applied the first answer, refuses the
        // second - the true one. The repair is what carries the microphone
        // across, and it also replaces the offer the rollback discarded.
        assertFalse(far.applies(SdpData(SignalType.ANSWER, answers[1].sdp!!)))
        assertEquals(1, android.disagreementsRepaired)
        far.setRemoteDescription(SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!))
        deliver(android, wire, answer(far.setLocalDescription().sdp))

        assertEquals("sendrecv", here.negotiatedAudio())
        assertEquals(here.negotiatedAudio(), far.negotiatedAudio(), "both ends complete the same negotiation")
        assertNull(wire.label)
    }

    // -- route 2: this side is handed a second answer it cannot apply --------

    @Test
    fun `BUG- a second answer arriving in stable is dropped and this side stays sendonly`() = runTest {
        // This side offers with a microphone. The far end answers once without
        // a microphone and once with.
        val here = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val far = DescribingPeerConnection("far")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)

        android.onNegotiationNeeded()
        val offered = SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!)

        far.setRemoteDescription(offered)
        val firstAnswer = far.setLocalDescription()
        deliver(android, wire, answer(firstAnswer.sdp))
        assertEquals("sendonly", here.negotiatedAudio())
        assertEquals("recvonly", far.negotiatedAudio())

        // The far end unmutes and re-applies the offer it still holds, which is
        // what re-answering a retransmitted offer amounts to.
        far.hasLocalAudio = true
        far.setRemoteDescription(offered)
        val secondAnswer = far.setLocalDescription()
        assertFalse(SdpShape.same(firstAnswer.sdp, secondAnswer.sdp))
        assertEquals("sendrecv", far.negotiatedAudio())

        // This side is `stable` and cannot apply it - but it can read it, and
        // what it says is that the far end completed a negotiation this side
        // did not.
        deliver(android, wire, answer(secondAnswer.sdp))
        assertNull(wire.label, "a description that cannot be applied is not a failed pair")
        assertEquals(1, android.disagreementsRepaired)
        assertTrue(here.refusals.isEmpty(), "the stack is never asked to do the impossible")

        far.setRemoteDescription(SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!))
        deliver(android, wire, answer(far.setLocalDescription().sdp))

        assertEquals("sendrecv", here.negotiatedAudio())
        assertEquals(far.negotiatedAudio(), here.negotiatedAudio(), "both ends complete the same negotiation")
    }

    // -- guards: what the fix must not start doing ---------------------------

    @Test
    fun `a duplicate answer with the same shape but different bytes starts no renegotiation`() = runTest {
        // The ordinary case, and by far the common one: the web client replays
        // its stored answer on every retransmitted offer by design, so a
        // desktop-to-Android call sees these routinely. A client that
        // renegotiated on each one would never stop renegotiating.
        val here = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)

        android.onNegotiationNeeded()
        far.setRemoteDescription(SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!))
        val first = far.setLocalDescription()
        deliver(android, wire, answer(first.sdp))
        assertEquals("sendrecv", here.negotiatedAudio())

        far.gatherCandidate(candidate)
        val again = far.localDescription()!!
        assertNotEquals(first.sdp, again.sdp, "the fixture must differ in bytes or it proves nothing")
        assertTrue(SdpShape.same(first.sdp, again.sdp))

        deliver(android, wire, answer(again.sdp))

        assertEquals(1, wire.count(SignalType.OFFER), "a duplicate answer is not a disagreement")
        assertEquals(1, android.duplicateAnswersDropped)
        assertEquals(0, android.disagreementsRepaired)
        assertEquals("sendrecv", here.negotiatedAudio())
        assertEquals(here.negotiatedAudio(), far.negotiatedAudio())
    }

    @Test
    fun `a duplicate answer leaves a connected pair connected`() = runTest {
        // The old behaviour threw out of the signal collector, and the engine's
        // catch relabelled the pair "failed" - a label the tile shows and
        // nothing rewrites, on a connection that was working perfectly.
        val here = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)

        android.onNegotiationNeeded()
        far.setRemoteDescription(SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!))
        val settled = far.setLocalDescription()
        deliver(android, wire, answer(settled.sdp))

        repeat(3) { deliver(android, wire, answer(settled.sdp)) }

        assertNull(wire.label, "a duplicate answer must not mark the pair failed")
        assertEquals(SignalingState.STABLE, here.signalingState())
        assertEquals("sendrecv", here.negotiatedAudio())
        assertEquals(1, wire.count(SignalType.OFFER))
    }

    @Test
    fun `a retransmitted offer answered identically starts no renegotiation`() = runTest {
        // Same guard from the answering seat: nothing about this connection
        // moved between the two, so the stored answer goes back out byte for
        // byte rather than the session being described again.
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val here = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)

        val first = far.setLocalDescription()
        deliver(android, wire, offer(first.sdp))
        far.setRemoteDescription(SdpData(SignalType.ANSWER, wire.last(SignalType.ANSWER).sdp!!))
        val applied = here.remoteDescriptions.size
        val described = here.localDescriptions.size

        far.gatherCandidate(candidate)
        deliver(android, wire, offer(far.localDescription()!!.sdp))

        val answers = wire.of(SignalType.ANSWER)
        assertEquals(2, answers.size)
        assertEquals(answers[0].sdp, answers[1].sdp, "the stored answer goes back out byte for byte")
        assertEquals(1, android.answersReplayed)
        assertEquals(applied, here.remoteDescriptions.size, "the connection is not described again")
        assertEquals(described, here.localDescriptions.size)
        assertEquals(0, wire.count(SignalType.OFFER), "answering again is not a renegotiation")
        assertEquals("sendrecv", here.negotiatedAudio())
    }

    // -- interop: the two far ends this pairs with ---------------------------

    @Test
    fun `an unfixed far end that drops answers in stable still ends up sendrecv`() = runTest {
        assertEquals("sendrecv" to "sendrecv", interop(fixedFarEnd = false))
    }

    @Test
    fun `a fixed far end that repairs by offer still ends up sendrecv`() = runTest {
        assertEquals("sendrecv" to "sendrecv", interop(fixedFarEnd = true))
    }

    /**
     * One call, from the far end's offer to a settled pair.
     *
     * The far end is the web client: it retransmits an offer it has had no
     * working media for, and it either drops an answer it cannot apply (the
     * unfixed one, which is what this was found against) or repairs from it
     * with an offer of its own (the fixed one). Either way the pair has to end
     * up sending both ways.
     */
    private suspend fun interop(fixedFarEnd: Boolean): Pair<String?, String?> {
        val here = DescribingPeerConnection("and")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)
        val far = WebFarEnd(DescribingPeerConnection("far").apply { hasLocalAudio = true }, fixed = fixedFarEnd)

        // It retransmits as soon as the first answer tells it nothing is
        // arriving, which is when a real one does.
        far.retransmitAfter = 0
        far.offer()

        var fromFar = 0
        var fromHere = 0
        var unmuted = false
        var steps = 0
        while (steps++ < 40) {
            var moved = false
            while (fromFar < far.sent.size) {
                val body = far.sent[fromFar++]
                deliver(android, wire, SignalEnvelope(impoliteDevice, body.type, roomId, sdp = body.sdp))
                moved = true
            }
            while (fromHere < wire.sent.size) {
                far.receive(wire.sent[fromHere++])
                moved = true
                // Somebody unmutes just after joining, so the first answer went
                // out without a microphone and the next one will not.
                if (!unmuted) {
                    here.hasLocalAudio = true
                    unmuted = true
                }
            }
            if (!moved) break
        }

        assertNull(wire.label)
        assertTrue(unmuted)
        return here.negotiatedAudio() to far.connection.negotiatedAudio()
    }

    // -- an offer nobody answers ---------------------------------------------

    @Test
    fun `a lost offer is asked about again until it is answered`() = runTest {
        // Profile 1 sends each signal exactly once. A lost offer used to leave
        // this side in `have-local-offer` for the rest of the call - and on the
        // impolite side every later offer from the far end is then ignored as a
        // collision, so neither end can get the pair out of it.
        val here = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire, scope = this)

        android.onNegotiationNeeded()
        val first = wire.last(SignalType.OFFER).sdp!!

        // The relay loses it. Gathering carries on meanwhile, so what goes back
        // out is the same session with more candidates on it - which the far
        // end reads as the same shape and can answer from store.
        here.gatherCandidate(candidate)
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        assertEquals(2, wire.count(SignalType.OFFER), "an unanswered offer must be asked about again")
        val again = wire.last(SignalType.OFFER).sdp!!
        assertNotEquals(first, again)
        assertTrue(SdpShape.same(first, again), "the same session, asked about again")

        far.setRemoteDescription(SdpData(SignalType.OFFER, again))
        deliver(android, wire, answer(far.setLocalDescription().sdp))
        assertEquals(SignalingState.STABLE, here.signalingState())

        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()
        assertEquals(2, wire.count(SignalType.OFFER), "an answered offer is never asked about again")
        assertEquals("sendrecv", here.negotiatedAudio())
        android.close()
    }

    @Test
    fun `a lost repair offer is re-sent and the pair converges`() = runTest {
        // The repair is the one offer that must not be lost: it is the only
        // thing that gets a split pair back together, and it goes out through
        // the same send-once transport as everything else.
        val here = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val far = DescribingPeerConnection("far")
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire, scope = this)

        android.onNegotiationNeeded()
        val offered = SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!)
        far.setRemoteDescription(offered)
        deliver(android, wire, answer(far.setLocalDescription().sdp))
        assertEquals("sendonly", here.negotiatedAudio())

        // The far end unmutes and answers the offer it still holds a second
        // time. This side cannot apply that, and repairs.
        far.hasLocalAudio = true
        far.setRemoteDescription(offered)
        deliver(android, wire, answer(far.setLocalDescription().sdp))
        assertEquals(1, android.disagreementsRepaired)
        assertEquals(2, wire.count(SignalType.OFFER))

        // And the repair is lost. Nothing else will ever notice: this side is
        // settled, the far end is settled, and they disagree.
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertEquals(3, wire.count(SignalType.OFFER), "a lost repair must be asked about again")

        far.setRemoteDescription(SdpData(SignalType.OFFER, wire.last(SignalType.OFFER).sdp!!))
        deliver(android, wire, answer(far.setLocalDescription().sdp))

        assertEquals("sendrecv", here.negotiatedAudio())
        assertEquals(far.negotiatedAudio(), here.negotiatedAudio())
        assertNull(wire.label)
        android.close()
    }

    @Test
    fun `a signal arriving after the link closes is ignored`() = runTest {
        // Closing runs on the thread that tears the call down and takes no
        // lock, so a signal can arrive against a connection that has just gone.
        // Pushing a description at a closed connection is refused, and that
        // refusal used to escape and mark a pair that had simply hung up as
        // failed.
        val far = DescribingPeerConnection("far").apply { hasLocalAudio = true }
        val here = DescribingPeerConnection("and").apply { hasLocalAudio = true }
        val wire = Wire()
        val android = link(politeDevice, impoliteDevice, here, wire)

        val first = far.setLocalDescription()
        deliver(android, wire, offer(first.sdp))
        val answered = wire.count(SignalType.ANSWER)

        android.close()
        far.gatherCandidate(candidate)
        deliver(android, wire, offer(far.localDescription()!!.sdp))
        deliver(android, wire, answer(first.sdp))

        assertNull(wire.label, "hanging up is not a failed pair")
        assertEquals(answered, wire.count(SignalType.ANSWER), "a closed link answers nothing")
        assertTrue(here.closed)
    }

    // -- the invariant: a repair is an offer, and offers do not echo ---------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `two fixed clients converge and go quiet however the transport misbehaves`() = runTest {
        // A repair is an OFFER, and an offer can never satisfy the other side's
        // "answer of an unknown shape" or "the same offer again" tests. So two
        // clients carrying this fix cannot repair at each other: whatever the
        // transport does to the order, the copies and the losses, the pair
        // settles on one session and then says nothing further.
        for (seed in 1..60) {
            val politeConnection = DescribingPeerConnection("pol").apply { hasLocalAudio = true }
            val impoliteConnection = DescribingPeerConnection("imp")
            val politeWire = Wire()
            val impoliteWire = Wire()
            val polite = link(politeDevice, impoliteDevice, politeConnection, politeWire)
            val impolite = link(impoliteDevice, politeDevice, impoliteConnection, impoliteWire)
            val transport = LossyTransport(Random(seed))

            polite.onNegotiationNeeded()
            var unmuted = false
            var owed = false
            var steps = 0
            while (steps++ < 400) {
                // Somebody unmutes partway through, which is the whole reason
                // two answers to one offer can differ. `addLocalTrack` asks the
                // connection to renegotiate, and libwebrtc only ever asks from
                // `stable`.
                if (!unmuted && transport.delivered >= 2) {
                    impoliteConnection.hasLocalAudio = true
                    unmuted = true
                    owed = true
                }
                if (owed && impoliteConnection.signalingState() == SignalingState.STABLE) {
                    owed = false
                    impolite.onNegotiationNeeded()
                }
                transport.collect(politeWire, to = impoliteDevice)
                transport.collect(impoliteWire, to = politeDevice)
                val next = transport.next() ?: break
                if (next.to == politeDevice) deliver(polite, politeWire, next.body)
                else deliver(impolite, impoliteWire, next.body)
            }
            transport.collect(politeWire, to = impoliteDevice)
            transport.collect(impoliteWire, to = politeDevice)

            val quiet = politeWire.sent.size + impoliteWire.sent.size
            testScheduler.advanceTimeBy(30_000)
            testScheduler.runCurrent()

            assertNull(politeWire.label, "seed $seed")
            assertNull(impoliteWire.label, "seed $seed")
            assertFalse(owed, "seed $seed: the unmute must have reached a renegotiation")
            assertTrue(transport.empty, "seed $seed: the transport must drain")
            assertEquals(quiet, politeWire.sent.size + impoliteWire.sent.size, "seed $seed: the pair must go quiet")
            assertTrue(quiet < 30, "seed $seed: $quiet signals is a storm, not a negotiation")
            assertEquals("sendrecv", politeConnection.negotiatedAudio(), "seed $seed")
            assertEquals("sendrecv", impoliteConnection.negotiatedAudio(), "seed $seed")
        }
    }

    /**
     * A relay that reorders, duplicates and loses.
     *
     * Every signal is enqueued twice, because a far end with nothing back
     * retransmits, and at most one copy of each is lost - at-least-once
     * delivery, which is all profile 1 can survive at all, since it retransmits
     * nothing itself.
     */
    private class LossyTransport(private val random: Random) {
        class Pending(val to: String, val body: SignalEnvelope, val id: Int)

        private val pending = mutableListOf<Pending>()
        private val landed = mutableSetOf<Int>()
        private val collected = mutableMapOf<Wire, Int>()
        private var ids = 0
        var delivered = 0
            private set

        val empty: Boolean get() = pending.isEmpty()

        /** Everything a side has said since the last look, in both copies. */
        fun collect(wire: Wire, to: String) {
            var index = collected.getOrDefault(wire, 0)
            while (index < wire.sent.size) {
                val id = ids++
                repeat(2) { pending += Pending(to, wire.sent[index], id) }
                index++
            }
            collected[wire] = index
        }

        fun next(): Pending? {
            while (pending.isNotEmpty()) {
                val chosen = pending.removeAt(random.nextInt(pending.size))
                val spare = pending.any { it.id == chosen.id } || chosen.id in landed
                if (spare && random.nextInt(3) == 0) continue
                landed += chosen.id
                delivered++
                return chosen
            }
            return null
        }
    }

    /**
     * The web client, in the two versions this pairs with.
     *
     * Small on purpose: it does what the other end of a real call does and
     * nothing else, so that a convergence failure here is this client's.
     */
    private class WebFarEnd(val connection: DescribingPeerConnection, private val fixed: Boolean) {
        val sent = mutableListOf<SignalEnvelope>()
        var delivered = 0
        var retransmitAfter = -1

        private var storedAnswer: String? = null
        private var storedAnswerShape: String? = null
        private var appliedOffer: String? = null
        private val appliedAnswers = mutableListOf<String>()
        private var answersSeen = 0

        suspend fun offer() {
            sent += SignalEnvelope("", SignalType.OFFER, "room", sdp = connection.setLocalDescription().sdp)
        }

        suspend fun receive(body: SignalEnvelope) {
            val sdp = body.sdp ?: return
            when (body.type) {
                SignalType.OFFER -> onOffer(sdp)
                SignalType.ANSWER -> onAnswer(sdp)
            }
        }

        private suspend fun onOffer(sdp: String) {
            // Impolite: an offer arriving on top of one of its own never
            // happened.
            if (connection.signalingState() != SignalingState.STABLE) return
            if (fixed && SdpShape.of(sdp) == appliedOffer && storedAnswer != null) {
                sent += SignalEnvelope("", SignalType.ANSWER, "room", sdp = storedAnswer)
                return
            }
            connection.setRemoteDescription(SdpData(SignalType.OFFER, sdp))
            appliedOffer = SdpShape.of(sdp)
            val answer = connection.setLocalDescription()
            storedAnswer = answer.sdp
            storedAnswerShape = SdpShape.of(answer.sdp)
            sent += SignalEnvelope("", SignalType.ANSWER, "room", sdp = answer.sdp)
        }

        private suspend fun onAnswer(sdp: String) {
            if (connection.signalingState() == SignalingState.HAVE_LOCAL_OFFER) {
                connection.setRemoteDescription(SdpData(SignalType.ANSWER, sdp))
                appliedAnswers += SdpShape.of(sdp)
                // The retransmission an offerer with no working media sends.
                if (answersSeen++ == retransmitAfter) {
                    connection.gatherCandidate("candidate:9 1 udp 1 203.0.113.9 5009 typ relay")
                    sent += SignalEnvelope("", SignalType.OFFER, "room", sdp = connection.localDescription()!!.sdp)
                }
                return
            }
            // Stable, with no offer outstanding. The unfixed client drops this
            // and never learns anything from it; the fixed one repairs.
            if (!fixed || SdpShape.of(sdp) in appliedAnswers) return
            appliedAnswers += SdpShape.of(sdp)
            sent += SignalEnvelope("", SignalType.OFFER, "room", sdp = connection.setLocalDescription().sdp)
        }
    }
}

/** [DescribingPeerConnection.setRemoteDescription] as a far end's own client
 *  would experience it: a refusal it has to live with, not an exception here. */
private suspend fun DescribingPeerConnection.applies(sdp: SdpData): Boolean =
    try {
        setRemoteDescription(sdp)
        true
    } catch (_: Exception) {
        false
    }
