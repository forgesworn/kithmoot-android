package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.support.FaultTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reliable signalling channel, against a wire that misbehaves.
 *
 * Every case here is a real failure from the call reliability diagnosis rather
 * than an invented one: one lost answer leaving a pair blind for the rest of a
 * call, a half-open socket that reconnected after 44 seconds and never
 * re-offered, a rebuilt connection offering into a connection the far end had
 * already replaced. None can be reproduced reliably by putting two handsets on
 * a desk; all of them take milliseconds here, because the schedule is virtual
 * time and the faults are seeded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalChannelTest {

    private val room = "ff".repeat(32)
    private val peer = "bb".repeat(32)
    private val ourConn = "1111111111111111"
    private val theirConn = "2222222222222222"

    /** No jitter: 0.5 puts the spread at exactly 1, so the schedule is
     *  1s, 2s, 4s, 8s, 8s and can be asserted to the millisecond. */
    private val noJitter: () -> Double = { 0.5 }

    private fun TestScope.channel(
        gen: Long = 1,
        conn: String = ourConn,
        peerConn: String? = null,
        expectedSeq: Long = 1,
        transmit: suspend (SignalEnvelope) -> Unit = {},
        deliver: suspend (SignalEnvelope) -> Unit = {},
        onNewerGeneration: (suspend (Long, SignalEnvelope) -> Unit)? = null,
        onForeignConnection: (suspend (String, SignalEnvelope) -> Unit)? = null,
        localDescription: ((String) -> String?)? = null,
        random: () -> Double = noJitter,
    ) = SignalChannel(
        gen = gen,
        conn = conn,
        toDevice = peer,
        roomId = room,
        scope = backgroundScope,
        transmit = transmit,
        deliver = deliver,
        peerConn = peerConn,
        expectedSeq = expectedSeq,
        onNewerGeneration = onNewerGeneration,
        onForeignConnection = onForeignConnection,
        localDescription = localDescription,
        random = random,
        now = { testScheduler.currentTime },
    )

    private fun out(type: String, sdp: String? = null, candidate: String? = null) =
        SignalEnvelope(toDevice = peer, type = type, roomId = room, sdp = sdp, candidate = candidate)

    /** A body as it arrives off the wire from the far end. */
    private fun inbound(
        type: String,
        seq: Long? = null,
        gen: Long? = 1,
        conn: String? = theirConn,
        peerConn: String? = null,
        ack: Long? = null,
        re: Long? = null,
        sdp: String? = null,
        candidate: String? = null,
    ) = SignalEnvelope(
        toDevice = peer,
        type = type,
        roomId = room,
        sdp = sdp,
        candidate = candidate,
        gen = gen,
        conn = conn,
        peerConn = peerConn,
        seq = seq,
        ack = ack,
        re = re,
    )

    /** Advance to exactly [ms] from now and run whatever falls due there. */
    private fun TestScope.tick(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `an offer is stamped, retransmitted, and stops once it is acknowledged`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })

        assertEquals(1, channel.send(out(SignalType.OFFER, sdp = "offer")))
        runCurrent()

        assertEquals(1, sent.size)
        assertEquals(1L, sent[0].seq)
        assertEquals(1L, sent[0].gen)
        assertEquals(ourConn, sent[0].conn)
        assertNull(sent[0].ack, "nothing has been received yet, so nothing is owed")
        assertEquals(1, channel.queueDepth)

        tick(1_000)
        assertEquals(2, sent.size, "an unacknowledged offer is asked about again")

        channel.receive(inbound(SignalType.ACK, ack = 1))
        runCurrent()
        assertEquals(0, channel.queueDepth)

        tick(30_000)
        assertEquals(2, sent.size, "an acknowledged offer is never asked about again")
    }

    @Test
    fun `an answer lost three times in a row still arrives`() = runTest {
        // The failure this whole module exists for. Profile 1 re-sent an offer
        // at most twice, three seconds apart, and never re-sent an answer at
        // all: three consecutive losses left the pair blind for the rest of
        // the call.
        val sent = mutableListOf<SignalEnvelope>()
        val arrived = mutableListOf<SignalEnvelope>()
        var losses = 0
        val channel = channel(
            transmit = {
                sent += it
                if (it.type == SignalType.ANSWER && losses < 3) losses++ else arrived += it
            },
        )

        channel.send(out(SignalType.ANSWER, sdp = "answer"))
        runCurrent()
        assertTrue(arrived.isEmpty())

        tick(1_000)
        tick(2_000)
        tick(4_000)

        assertEquals(3, losses)
        assertEquals(1, arrived.size, "the fourth attempt is the one that lands")
        assertEquals(1L, arrived.single().seq, "and it is the same signal, under its original seq")
    }

    @Test
    fun `the backoff is one, two, four, eight and then eight seconds for ever`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()

        var expected = 1
        for (step in listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L)) {
            tick(step - 1)
            assertEquals(expected, sent.size, "nothing may go out before $step ms have passed")
            tick(1)
            expected += 1
            assertEquals(expected, sent.size, "a retransmission is due at $step ms")
        }
    }

    @Test
    fun `the backoff is spread by twenty per cent either way`() = runTest {
        // Two sides that lost the same window must not retransmit in lockstep
        // on every attempt, so every step is jittered. The bounds are what the
        // relay budget was reasoned against.
        val early = mutableListOf<SignalEnvelope>()
        val earliest = channel(transmit = { early += it }, random = { 0.0 })
        earliest.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()
        tick(799)
        assertEquals(1, early.size)
        tick(1)
        assertEquals(2, early.size, "the shortest a one second step may be is 800ms")

        val late = mutableListOf<SignalEnvelope>()
        val latest = channel(transmit = { late += it }, random = { 1.0 })
        latest.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()
        tick(1_199)
        assertEquals(1, late.size)
        tick(1)
        assertEquals(2, late.size, "the longest a one second step may be is 1200ms")
    }

    @Test
    fun `unacknowledged candidates are retransmitted as one batch covering their seq range`() = runTest {
        // Amendment A2. Retransmitting thirty trickled candidates individually
        // would burn the relay's 120-per-20-seconds budget on its own, and is
        // pointless beside a re-sent description that already contains them.
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })

        channel.send(out(SignalType.OFFER, sdp = "offer"))
        channel.send(out(SignalType.ICE, candidate = "candidate:1"))
        channel.send(out(SignalType.ICE, candidate = "candidate:2"))
        channel.send(out(SignalType.ICE, candidate = "candidate:3"))
        runCurrent()
        assertEquals(4, sent.size)

        sent.clear()
        tick(1_000)

        assertEquals(2, sent.size, "one offer and one batch, not four signals")
        assertEquals(SignalType.OFFER, sent[0].type)
        assertEquals(1L, sent[0].seq)

        val batch = sent[1]
        assertEquals(SignalType.ICE, batch.type)
        assertEquals(2L, batch.first)
        assertEquals(4L, batch.seq)
        assertEquals(listOf("candidate:1", "candidate:2", "candidate:3"), batch.candidates)
        assertNull(
            batch.candidate,
            "a batch that also carried the single-candidate field would have one applied twice",
        )
    }

    @Test
    fun `a batched candidate carries only the candidate string, as max-bundle lets Android rely on`() = runTest {
        // Section 6 step 6: Android puts a bare candidate on the wire and
        // relies on max-bundle meaning one transport, so batching must not
        // start carrying m-line indices it would then have to agree about.
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })
        channel.send(out(SignalType.ICE, candidate = IceCandidateData("candidate:1 1 udp 1 10.0.0.1 1 typ host").toWire()))
        runCurrent()
        sent.clear()
        tick(1_000)

        val batch = sent.single()
        assertEquals(1, batch.candidates?.size)
        assertEquals(
            IceCandidateData("candidate:1 1 udp 1 10.0.0.1 1 typ host"),
            IceCandidateData.fromWire(batch.candidates!!.single()),
        )
    }

    @Test
    fun `a retransmitted offer carries the description the connection holds now`() = runTest {
        // The re-sent description already contains every candidate gathered
        // since, which is the other half of why candidates do not need
        // re-sending beside it.
        val sent = mutableListOf<SignalEnvelope>()
        var current = "offer-bare"
        val channel = channel(
            transmit = { sent += it },
            localDescription = { type -> if (type == SignalType.OFFER) current else null },
        )
        channel.send(out(SignalType.OFFER, sdp = "offer-bare"))
        runCurrent()
        current = "offer-with-candidates"
        tick(1_000)

        assertEquals("offer-with-candidates", sent.last().sdp)
        assertEquals(1L, sent.last().seq, "under the original seq: it is the same proposal")
    }

    @Test
    fun `signals that arrive out of order are buffered and released in order`() = runTest {
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(deliver = { delivered += it })

        channel.receive(inbound(SignalType.ICE, seq = 3, candidate = "candidate:3"))
        channel.receive(inbound(SignalType.ICE, seq = 2, candidate = "candidate:2"))
        assertTrue(delivered.isEmpty(), "a gap must stall the stream, not be stepped over")

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))

        assertEquals(listOf(1L, 2L, 3L), delivered.map { it.seq })
    }

    @Test
    fun `a gap is deliberately not acknowledged, so the far end keeps asking for it`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })

        channel.receive(inbound(SignalType.ICE, seq = 2, candidate = "candidate:2"))
        tick(1_000)

        assertTrue(sent.isEmpty(), "acknowledging cumulatively here would claim seq 1 arrived")
        assertEquals(0, channel.ackedThrough)
    }

    @Test
    fun `a duplicate is delivered once and acknowledged at once`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it }, deliver = { delivered += it })

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        runCurrent()
        assertEquals(1, delivered.size)
        assertTrue(sent.isEmpty(), "the acknowledgement waits for something to ride on")

        tick(ACK_DELAY_MS)
        assertEquals(1, sent.size)
        assertEquals(SignalType.ACK, sent[0].type)
        assertEquals(1L, sent[0].ack)

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        runCurrent()
        assertEquals(1, delivered.size, "idempotent: it has already been acted on")
        assertEquals(2, sent.size, "a duplicate means our acknowledgement was lost, so do not wait")
        assertEquals(SignalType.ACK, sent[1].type)
    }

    @Test
    fun `an acknowledgement rides on whatever this side was already sending`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        channel.send(out(SignalType.ANSWER, sdp = "answer"))
        runCurrent()

        assertEquals(1, sent.size, "the common case costs no acknowledgement signal at all")
        assertEquals(SignalType.ANSWER, sent[0].type)
        assertEquals(1L, sent[0].ack)
        assertEquals(theirConn, sent[0].peerConn, "bound to the connection the first signal came from")

        tick(ACK_DELAY_MS)
        assertEquals(1, sent.size, "and the delayed acknowledgement is cancelled, not sent twice")
    }

    @Test
    fun `a burst is acknowledged once`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })

        for (seq in 1L..20L) channel.receive(inbound(SignalType.ICE, seq = seq, candidate = "candidate:$seq"))
        tick(ACK_DELAY_MS)

        assertEquals(1, sent.size, "twenty trickled candidates cost one acknowledgement")
        assertEquals(20L, sent.single().ack)
    }

    @Test
    fun `an acknowledgement is cumulative`() = runTest {
        val channel = channel()
        channel.send(out(SignalType.ICE, candidate = "candidate:1"))
        channel.send(out(SignalType.ICE, candidate = "candidate:2"))
        channel.send(out(SignalType.ICE, candidate = "candidate:3"))
        runCurrent()
        assertEquals(3, channel.queueDepth)

        channel.receive(inbound(SignalType.ACK, ack = 2))
        runCurrent()

        assertEquals(1, channel.queueDepth, "everything at or below the acknowledged seq is done with")
    }

    @Test
    fun `an answer is proof its offer arrived, whatever the acknowledgement said`() = runTest {
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(deliver = { delivered += it })
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()
        assertEquals(1L, channel.outstandingOffer)

        channel.receive(inbound(SignalType.ANSWER, seq = 1, re = 1, sdp = "answer"))
        runCurrent()

        assertEquals(1, delivered.size)
        assertEquals(0, channel.queueDepth)
        assertNull(channel.outstandingOffer)
    }

    @Test
    fun `an answer to an offer we are no longer waiting on is acknowledged and ignored`() = runTest {
        // A retransmitted offer and its answer can cross. Applying an answer to
        // an offer that has since been superseded puts the connection into a
        // session neither side described.
        val sent = mutableListOf<SignalEnvelope>()
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it }, deliver = { delivered += it })
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()
        sent.clear()

        channel.receive(inbound(SignalType.ANSWER, seq = 1, re = 7, sdp = "stale-answer"))
        runCurrent()

        assertTrue(delivered.isEmpty(), "it does not answer the proposal we hold")
        assertEquals(1, channel.queueDepth, "so our own offer is still outstanding")
        assertEquals(1, channel.ackedThrough, "but it did arrive, and the far end should stop asking")
        tick(ACK_DELAY_MS)
        assertEquals(SignalType.ACK, sent.first().type)
    }

    @Test
    fun `an answer with no re is applied, because a peer that does not sequence is not a peer to refuse`() = runTest {
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(deliver = { delivered += it })
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()

        channel.receive(inbound(SignalType.ANSWER, seq = 1, sdp = "answer"))
        runCurrent()

        assertEquals(1, delivered.size)
    }

    @Test
    fun `the unacknowledged queue is flushed the moment the transport comes back`() = runTest {
        // The 44 second case: a client whose relay sockets went half-open
        // reconnected, resubscribed, and never re-offered, because nothing
        // local had changed and the backoff was mid-step.
        val transport = FaultTransport(backgroundScope)
        val channel = channel(transmit = transport::send)

        transport.connected = false
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()
        tick(1_000)
        tick(2_000)
        assertEquals(3, transport.published.size)
        assertTrue(transport.arrived.isEmpty(), "everything so far went into a half-open socket")

        transport.connected = true
        val before = transport.published.size
        channel.reconnected()
        runCurrent()

        assertEquals(before + 1, transport.published.size, "a reconnect is worth a publish of its own")
        assertEquals(1, transport.arrived.size)
        assertEquals(1L, transport.arrived.single().seq)
    }

    @Test
    fun `a reconnect does not charge a backoff step`() = runTest {
        // The attempt that was waiting has not happened yet; charging it would
        // push the next genuine retransmission further out for no reason.
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()

        tick(500)
        channel.reconnected()
        runCurrent()
        val after = sent.size

        tick(500)
        assertEquals(after + 1, sent.size, "the step that was already running still fires when it was due")
    }

    @Test
    fun `a publish that was rejected outright is retried without waiting out the step`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()

        channel.retransmitNow()
        runCurrent()

        assertEquals(2, sent.size, "nothing on the far end will ever ask for a signal that never left")
    }

    @Test
    fun `an older generation is dropped and answered with one sync per two seconds`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(gen = 3, transmit = { sent += it }, deliver = { delivered += it })

        channel.receive(inbound(SignalType.OFFER, seq = 1, gen = 2, sdp = "old-offer"))
        channel.receive(inbound(SignalType.OFFER, seq = 1, gen = 2, sdp = "old-offer"))
        runCurrent()

        assertTrue(delivered.isEmpty(), "it is addressed to a connection that no longer exists")
        assertEquals(1, sent.size)
        assertEquals(SignalType.SYNC, sent[0].type)
        assertEquals(3L, sent[0].gen)
        assertNull(sent[0].peerConn, "the connection it would name is precisely the one that has gone")

        tick(SYNC_INTERVAL_MS)
        channel.receive(inbound(SignalType.OFFER, seq = 1, gen = 2, sdp = "old-offer"))
        runCurrent()
        assertEquals(2, sent.size)
    }

    @Test
    fun `a newer generation is reported, never delivered and never acknowledged`() = runTest {
        // Acknowledging it would stop the far end asking before anything had
        // adopted it, and the channel cannot adopt: that means closing a
        // connection and building another.
        val sent = mutableListOf<SignalEnvelope>()
        val delivered = mutableListOf<SignalEnvelope>()
        val seen = mutableListOf<Long>()
        val channel = channel(
            gen = 1,
            transmit = { sent += it },
            deliver = { delivered += it },
            onNewerGeneration = { gen, _ -> seen += gen },
        )

        channel.receive(inbound(SignalType.OFFER, seq = 1, gen = 2, sdp = "offer"))
        tick(1_000)

        assertEquals(listOf(2L), seen)
        assertTrue(delivered.isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(0, channel.ackedThrough)
    }

    @Test
    fun `a signal from a connection this channel is not bound to is reported and dropped`() = runTest {
        val delivered = mutableListOf<SignalEnvelope>()
        val foreign = mutableListOf<String>()
        val channel = channel(deliver = { delivered += it }, onForeignConnection = { conn, _ -> foreign += conn })

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        channel.receive(inbound(SignalType.OFFER, seq = 2, conn = "3333333333333333", sdp = "offer"))
        runCurrent()

        assertEquals(theirConn, channel.peerConn)
        assertEquals(listOf("3333333333333333"), foreign)
        assertEquals(1, delivered.size)
    }

    @Test
    fun `a signal addressed to a connection this side has replaced is ignored outright`() = runTest {
        val delivered = mutableListOf<SignalEnvelope>()
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it }, deliver = { delivered += it })

        channel.receive(inbound(SignalType.OFFER, seq = 1, peerConn = "9999999999999999", sdp = "offer"))
        tick(1_000)

        assertTrue(delivered.isEmpty(), "its seq space is not ours, so applying it would corrupt the stream")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the out-of-order buffer is bounded and the far end is left to re-send the hole`() = runTest {
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(deliver = { delivered += it })

        for (seq in 2L..(MAX_BUFFERED_SIGNALS + 11L)) {
            channel.receive(inbound(SignalType.ICE, seq = seq, candidate = "candidate:$seq"))
        }
        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        runCurrent()

        assertEquals(
            listOf(1L),
            delivered.map { it.seq },
            "the oldest buffered signals were dropped, so the stream stops at the first hole",
        )
    }

    @Test
    fun `a batch stands in for every seq it covers`() = runTest {
        // The whole reason `first` is on the wire. The signals a batch
        // coalesces were sent once each and will never be sent again
        // individually, so a receiver that only looked at `seq` would hold the
        // batch behind a gap nothing was ever going to fill - and the pair
        // would never hear another candidate.
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(deliver = { delivered += it })

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        // Seqs 2 and 3 were lost. Their retransmission is one batch.
        channel.receive(
            inbound(SignalType.ICE, seq = 3).copy(first = 2, candidates = listOf("candidate:2", "candidate:3")),
        )
        runCurrent()

        assertEquals(listOf(1L, 3L), delivered.map { it.seq })
        assertEquals(3, channel.ackedThrough, "the whole range is accounted for, not just its last seq")
    }

    @Test
    fun `a batch whose range starts beyond the gap still waits`() = runTest {
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(deliver = { delivered += it })

        channel.receive(
            inbound(SignalType.ICE, seq = 5).copy(first = 3, candidates = listOf("candidate:3")),
        )
        runCurrent()
        assertTrue(delivered.isEmpty(), "seqs 1 and 2 are still missing")

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        channel.receive(inbound(SignalType.ICE, seq = 2, candidate = "candidate:2"))
        runCurrent()

        assertEquals(listOf(1L, 2L, 5L), delivered.map { it.seq }, "and is released the moment they arrive")
    }

    @Test
    fun `a batch already wholly seen is acknowledged and not delivered twice`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it }, deliver = { delivered += it })

        channel.receive(inbound(SignalType.OFFER, seq = 1, sdp = "offer"))
        channel.receive(inbound(SignalType.ICE, seq = 2, candidate = "candidate:2"))
        runCurrent()
        sent.clear()

        channel.receive(
            inbound(SignalType.ICE, seq = 2).copy(first = 2, candidates = listOf("candidate:2")),
        )
        runCurrent()

        assertEquals(2, delivered.size)
        assertEquals(SignalType.ACK, sent.single().type)
    }

    @Test
    fun `a health signal is delivered but never sequenced`() = runTest {
        // Its value is entirely in being current: a stale copy of what is being
        // received right now is worse than none.
        val sent = mutableListOf<SignalEnvelope>()
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it }, deliver = { delivered += it })

        channel.sendUnreliable(
            SignalEnvelope(peer, SignalType.HEALTH, room, rx = mapOf("camera" to "dead")),
        )
        runCurrent()
        assertEquals(1, sent.size)
        assertNull(sent[0].seq)
        assertEquals(1L, sent[0].gen)
        assertEquals(0, channel.queueDepth, "nothing to retransmit: it would be a lie by the time it landed")

        channel.receive(inbound(SignalType.HEALTH).copy(rx = mapOf("mic" to "dead")))
        runCurrent()
        assertEquals(mapOf("mic" to "dead"), delivered.single().rx)
    }

    @Test
    fun `a dropped signal stops being asked about`() = runTest {
        // The polite side of an in-generation glare rolls its own offer back,
        // and the connection no longer holds that proposal.
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })
        val offer = channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()

        channel.drop(offer)
        assertEquals(0, channel.queueDepth)
        assertNull(channel.outstandingOffer)

        tick(30_000)
        assertEquals(1, sent.size)
    }

    @Test
    fun `closing drops the queue and stops the retransmission for good`() = runTest {
        val sent = mutableListOf<SignalEnvelope>()
        val channel = channel(transmit = { sent += it })
        channel.send(out(SignalType.OFFER, sdp = "offer"))
        runCurrent()

        channel.close()

        tick(60_000)
        assertEquals(1, sent.size)
        assertEquals(0, channel.queueDepth)
        assertEquals(0, channel.send(out(SignalType.OFFER, sdp = "offer")), "and nothing more is accepted")
    }

    @Test
    fun `a channel opened to answer an unbroken stream expects that stream's seq`() = runTest {
        // Amendment A1's polite side discards its connection object on
        // generation-opening glare. The offer that caused it is at whatever seq
        // the far end's unbroken stream had reached; a fresh channel expecting
        // 1 would buffer it for ever and the pair would never be answered.
        val delivered = mutableListOf<SignalEnvelope>()
        val channel = channel(expectedSeq = 7, deliver = { delivered += it })

        channel.receive(inbound(SignalType.OFFER, seq = 7, sdp = "offer"))
        runCurrent()

        assertEquals(listOf(7L), delivered.map { it.seq })
    }

    @Test
    fun `two channels converge over a wire that drops, delays, duplicates and reorders`() = runTest {
        // The whole point, end to end: an offer and its answer, through a relay
        // losing two signals in five, doubling one in five and holding each for
        // an arbitrary moment - which is what reorders them.
        //
        // It is also the proof that a negotiator may send from inside its own
        // delivery: the answer below is sent from within the offer's delivery
        // callback, on the same thread that is inside the receiving channel.
        val seeded = Random(42)
        val aToB = FaultTransport(backgroundScope, seed = 7)
        val bToA = FaultTransport(backgroundScope, seed = 11)
        for (wire in listOf(aToB, bToA)) {
            wire.dropRate = 0.4
            wire.duplicateRate = 0.2
            wire.maxDelayMs = 300
        }

        val aDelivered = mutableListOf<SignalEnvelope>()
        val bDelivered = mutableListOf<SignalEnvelope>()
        lateinit var b: SignalChannel

        val a = SignalChannel(
            gen = 1,
            conn = ourConn,
            toDevice = peer,
            roomId = room,
            scope = backgroundScope,
            transmit = aToB::send,
            deliver = { aDelivered += it },
            random = seeded::nextDouble,
            now = { testScheduler.currentTime },
        )
        b = SignalChannel(
            gen = 1,
            conn = theirConn,
            toDevice = peer,
            roomId = room,
            scope = backgroundScope,
            transmit = bToA::send,
            deliver = { body ->
                bDelivered += body
                if (body.type == SignalType.OFFER) {
                    b.send(SignalEnvelope(peer, SignalType.ANSWER, room, sdp = "answer", re = body.seq))
                }
            },
            random = seeded::nextDouble,
            now = { testScheduler.currentTime },
        )
        aToB.sink = { b.receive(it) }
        bToA.sink = { a.receive(it) }

        a.send(out(SignalType.OFFER, sdp = "offer"))
        a.send(out(SignalType.ICE, candidate = "candidate:1"))
        a.send(out(SignalType.ICE, candidate = "candidate:2"))
        runCurrent()

        tick(120_000)

        assertEquals(
            listOf(SignalType.OFFER, SignalType.ICE, SignalType.ICE),
            bDelivered.map { it.type },
            "everything arrives, exactly once each, in the order it was sent",
        )
        assertEquals(listOf(SignalType.ANSWER), aDelivered.map { it.type })
        assertEquals(0, a.queueDepth, "and both sides know it")
        assertEquals(0, b.queueDepth)
        assertTrue(aToB.published.size > 3, "which took retransmission over a wire this bad")
    }

    @Test
    fun `a wire that only delays still delivers in order`() = runTest {
        // Reordering on its own, with nothing lost: independent latencies are
        // how a relay fanning out over several sockets shuffles a burst.
        val wire = FaultTransport(backgroundScope, seed = 3)
        wire.maxDelayMs = 500
        val delivered = mutableListOf<SignalEnvelope>()
        val receiver = channel(conn = theirConn, deliver = { delivered += it })
        wire.sink = { receiver.receive(it) }
        val sender = channel(transmit = wire::send)

        sender.send(out(SignalType.OFFER, sdp = "offer"))
        for (i in 1..8) sender.send(out(SignalType.ICE, candidate = "candidate:$i"))
        runCurrent()
        tick(2_000)

        assertEquals((1L..9L).toList(), delivered.map { it.seq })
        assertTrue(
            wire.arrived.map { it.seq } != delivered.map { it.seq },
            "the wire really did shuffle them, so the ordering above is the channel's doing",
        )
    }
}
