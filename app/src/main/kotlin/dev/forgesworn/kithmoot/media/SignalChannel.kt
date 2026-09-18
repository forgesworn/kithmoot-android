package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The reliable signalling channel of the call reliability design, section 3.2.
 * Mirrors the web client's `src/signal-channel.ts` decision for decision.
 *
 * Perfect negotiation assumes the signalling path delivers. Ours does not: a
 * signal is an ephemeral event on a public relay, handed to whoever happens to
 * be subscribed at the instant it arrives and kept for nobody. One lost answer
 * leaves a pair blind for the rest of the call, because the side that is owed
 * it has no way to know it was ever sent. This is the layer that makes the path
 * reliable: sequence numbers, cumulative acknowledgement, retransmission until
 * acknowledged, and in-order idempotent delivery.
 *
 * Deliberately pure. It owns no peer connection, no relay and no crypto - it is
 * handed a [transmit] for the wire and a [deliver] for the negotiator, and
 * everything it does is a function of what it has been told. That is what lets
 * the awkward cases (a 44 second half-open socket, three answers lost in a row,
 * a generation superseded mid-flight) be tested in milliseconds against virtual
 * time rather than inferred from two handsets on a desk.
 */

/** The first step of the retransmission backoff. Short, because the common
 *  case is a single lost signal and the pair is blind until it is replaced. */
const val SIGNAL_RETRY_MS: Long = 1_000

/**
 * The longest an unacknowledged signal waits between re-sends.
 *
 * The schedule is 1s, 2s, 4s, 8s and then 8s for ever, each with +/-20% jitter
 * so two sides that lost the same window do not retransmit in lockstep.
 *
 * Against the budget: the relay guard allows 120 signals per sending device per
 * 20 second window. A wedged connection in steady state costs one retransmission
 * per 8 seconds - 2.5 per window - and coalescing is what keeps it at one. An
 * offer or answer re-sends as a single current local description, and every
 * unacknowledged candidate leaves as one batched `ice` no matter how many were
 * gathered (amendment A2).
 */
const val MAX_SIGNAL_RETRY_MS: Long = 8_000

/** How much each backoff step is spread by, either way. */
const val SIGNAL_RETRY_JITTER: Double = 0.2

/**
 * How long an acknowledgement waits for something to ride on.
 *
 * Long enough that an offer's burst of candidates is acknowledged once rather
 * than twenty times, and that the answer this side is about to send carries the
 * acknowledgement itself; short enough that the far end's first backoff step
 * never expires waiting for it.
 */
const val ACK_DELAY_MS: Long = 200

/**
 * How many out-of-order signals are held before the oldest is dropped.
 *
 * Bounded because an unbounded buffer fed by a hostile or broken sender is a
 * free memory sink, and cheap to bound because dropping is not loss: the far
 * end retransmits anything it has not seen acknowledged, for as long as the
 * pair exists.
 */
const val MAX_BUFFERED_SIGNALS: Int = 64

/** At most one `sync` per pair per this interval, per section 2.3. */
const val SYNC_INTERVAL_MS: Long = 2_000

/** The retransmission schedule. Defaults are the constants above; tests shorten them. */
data class SignalRetryTiming(
    val intervalMs: Long = SIGNAL_RETRY_MS,
    val maxIntervalMs: Long = MAX_SIGNAL_RETRY_MS,
    val jitter: Double = SIGNAL_RETRY_JITTER,
    val ackDelayMs: Long = ACK_DELAY_MS,
    val syncMs: Long = SYNC_INTERVAL_MS,
    val bufferLimit: Int = MAX_BUFFERED_SIGNALS,
)

/**
 * One direction's sender and the other direction's receiver for a single
 * connection.
 *
 * Both halves are here because they share one wire and one acknowledgement:
 * every outgoing body carries what this side has received, which is what makes
 * acknowledgement free in the common case.
 *
 * A channel never changes its generation: a new generation is a new connection
 * and therefore a new channel.
 *
 * Threading: [lock] guards every field, and nothing suspends while it is held.
 * Callbacks - [transmit], [deliver] and the two reports - are invoked outside
 * it, so a negotiator that sends from inside its own delivery cannot deadlock
 * against the channel that delivered to it.
 */
class SignalChannel(
    /** The generation this channel belongs to. */
    val gen: Long,
    /** This side's connection instance id (16 hex), stamped on everything. */
    val conn: String,
    /** Which device the bare `ack` and `sync` bodies are addressed to. */
    private val toDevice: String,
    /** Which room they name. */
    private val roomId: String,
    /** Timers live here. In tests this is the `TestScope`, so the whole
     *  schedule is virtual time and the assertions are exact. */
    private val scope: CoroutineScope,
    /** Put a body on the wire. Failure is the caller's business; a publish that
     *  never left the device is repaired by [retransmitNow]. */
    private val transmit: suspend (SignalEnvelope) -> Unit,
    /** Hand a signal to the negotiator. Called once per `(conn, seq)`, in seq
     *  order, and never for something this channel consumed itself. */
    private val deliver: suspend (SignalEnvelope) -> Unit,
    /** The remote connection being addressed, when it is already known. It is
     *  otherwise learned from the first signal that arrives. */
    peerConn: String? = null,
    /**
     * The first seq this channel should expect from the far end.
     *
     * Only ever anything but 1 when a connection is replaced while the far
     * end's is not: amendment A1's polite side discards its connection object
     * on generation-opening glare, and the offer that caused it is at whatever
     * seq the far end's unbroken stream had reached. A fresh channel expecting
     * 1 would buffer that offer for ever and the pair would never be answered.
     */
    expectedSeq: Long = 1,
    /**
     * A signal from a newer generation arrived.
     *
     * The channel does not act on it - adopting a generation means closing a
     * connection and building another, which is the negotiator's job (section
     * 3.3). It reports it and stops, and the offending signal is neither
     * delivered nor acknowledged, so the far end keeps asking until the new
     * generation's channel answers.
     */
    private val onNewerGeneration: (suspend (Long, SignalEnvelope) -> Unit)? = null,
    /**
     * A signal arrived at this generation from a connection that is not the one
     * this channel is bound to. Section 3.3 calls that a protocol error and
     * rebuilds; the channel only reports it, and drops the signal.
     */
    private val onForeignConnection: (suspend (String, SignalEnvelope) -> Unit)? = null,
    /**
     * The connection's current local description, used when an offer or answer
     * is retransmitted. What goes back out is the description the connection
     * holds *now*, under the original seq: same session, same ICE credentials,
     * and by now carrying every candidate gathered since.
     */
    private val localDescription: ((type: String) -> String?)? = null,
    private val timing: SignalRetryTiming = SignalRetryTiming(),
    /** Where the backoff jitter comes from. */
    private val random: () -> Double = { Random.nextDouble() },
    /** Only the `sync` rate limit reads this; the rest of the schedule is
     *  [delay] on [scope], which a test scheduler already owns. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private data class Unacked(val seq: Long, val body: SignalEnvelope)

    private val lock = Any()

    private var peerConnection: String? = peerConn
    private var isClosed = false

    // Sender.
    private var outSeq: Long = 0
    private var queue = mutableListOf<Unacked>()
    private var attempt: Int = 0
    private var retryJob: Job? = null
    private var outstanding: Long? = null

    // Receiver.
    private var expected: Long = if (expectedSeq >= 1) expectedSeq else 1
    private val buffer = LinkedHashMap<Long, SignalEnvelope>()
    private var ackJob: Job? = null
    private var lastSyncAt: Long? = null

    /** The remote connection this channel is bound to, once it is known. */
    val peerConn: String? get() = synchronized(lock) { peerConnection }

    /** How many signals are still waiting to be acknowledged. Diagnostics. */
    val queueDepth: Int get() = synchronized(lock) { queue.size }

    /** The highest contiguous seq received from the far end. */
    val ackedThrough: Long get() = synchronized(lock) { expected - 1 }

    /** The seq of the local offer still waiting for its answer, if any. */
    val outstandingOffer: Long? get() = synchronized(lock) { outstanding }

    val closed: Boolean get() = synchronized(lock) { isClosed }

    /**
     * Send reliably: stamp a seq, queue it, publish it and keep publishing
     * until the far end says it arrived.
     *
     * Returns the assigned seq, or 0 if the channel is closed.
     */
    suspend fun send(body: SignalEnvelope): Long {
        val stamped = synchronized(lock) {
            if (isClosed) return 0
            val seq = ++outSeq
            val queued = body.copy(seq = seq)
            if (body.type == SignalType.OFFER) outstanding = seq
            queue += Unacked(seq, queued)
            stampLocked(queued)
        }
        transmit(stamped)
        armRetry()
        return stamped.seq ?: 0
    }

    /**
     * Send once, without a seq and without retransmission.
     *
     * For signals whose value is entirely in being current: `health` (section
     * 3.4) describes what is being received right now, and a stale copy of it
     * is worse than none. It still carries the generation, the connection ids
     * and a piggybacked acknowledgement, so it is never wasted.
     */
    suspend fun sendUnreliable(body: SignalEnvelope) {
        val stamped = synchronized(lock) {
            if (isClosed) return
            stampLocked(body)
        }
        transmit(stamped)
    }

    /**
     * A signal arrived for this connection, already unwrapped and validated.
     *
     * Everything section 3.2's receiver block describes happens here:
     * generation filtering, acknowledgement, deduplication, buffering and
     * in-order release.
     */
    suspend fun receive(body: SignalEnvelope) {
        val outcome = synchronized(lock) { receiveLocked(body) } ?: return
        for (envelope in outcome.transmit) transmit(envelope)
        // Armed before the delivery, not after it. The negotiator usually
        // answers from inside its own delivery, and that answer stamps the
        // acknowledgement on itself and cancels this timer - which is the whole
        // reason the common case costs no `ack` signal at all. Arming
        // afterwards would send one anyway, two hundred milliseconds behind an
        // answer that already carried it.
        if (outcome.armAck) armAck()
        for (envelope in outcome.deliver) deliver(envelope)
        outcome.newerGeneration?.let { onNewerGeneration?.invoke(it, body) }
        outcome.foreignConnection?.let { onForeignConnection?.invoke(it, body) }
    }

    /**
     * Stop asking about a signal this side has given up on.
     *
     * Exactly one caller: the polite side of an in-generation glare, which
     * rolls its own offer back so the far end's can land. The connection no
     * longer holds that proposal, so retransmitting it would re-open a
     * negotiation this side has already conceded - and, since the
     * retransmission re-sends the connection's *current* local description,
     * would re-send the far end's own answer back at it.
     */
    fun drop(seq: Long): Unit = synchronized(lock) {
        if (isClosed) return
        queue = queue.filterNot { it.seq == seq }.toMutableList()
        if (outstanding == seq) outstanding = null
        if (queue.isEmpty()) idleLocked()
    }

    /**
     * The transport came back. Publish everything outstanding immediately.
     *
     * This is the 44 second case from the diagnosis: a client whose relay
     * sockets went half-open reconnected, resubscribed, and never re-offered,
     * because nothing local had changed and the backoff was mid-step. A
     * reconnect is new information about the wire, so it is worth a publish on
     * its own.
     *
     * It does not advance the backoff. The attempt that was waiting has not
     * happened yet, and charging a step for a reconnect would push the next
     * genuine retransmission further out for no reason.
     */
    suspend fun reconnected() {
        val outgoing = synchronized(lock) {
            if (isClosed) return
            val bodies = mutableListOf<SignalEnvelope>()
            if (expected - 1 > 0) bodies += stampLocked(bare(SignalType.ACK))
            if (queue.isNotEmpty()) bodies += coalesceLocked()
            bodies
        }
        for (envelope in outgoing) transmit(envelope)
        armRetry()
    }

    /**
     * Publish what is outstanding now, without advancing the backoff.
     *
     * The caller calls this when a relay publish was rejected outright: the
     * signal never left the device, so nothing on the far end will ever ask for
     * it and waiting out the step accomplishes nothing.
     */
    suspend fun retransmitNow() {
        val outgoing = synchronized(lock) {
            if (isClosed || queue.isEmpty()) return
            coalesceLocked()
        }
        for (envelope in outgoing) transmit(envelope)
        armRetry()
    }

    /**
     * The connection is gone - closed, superseded by a newer generation, or the
     * peer has left the roster. Drop the queue and stop.
     *
     * Nothing short of this stops the retransmission. Whether a peer is still
     * there is the roster's call and the rebuild ladder's, never a retry
     * counter's: a two-retry budget is exactly what left pairs wedged for the
     * rest of a call when signalling was lost for more than six seconds.
     */
    fun close(): Unit = synchronized(lock) {
        if (isClosed) return
        isClosed = true
        queue.clear()
        buffer.clear()
        outstanding = null
        retryJob?.cancel()
        retryJob = null
        ackJob?.cancel()
        ackJob = null
    }

    // ----------------------------------------------------------------- receive

    /** What one [receive] decided, to be carried out with the lock released. */
    private class Outcome {
        val transmit = mutableListOf<SignalEnvelope>()
        val deliver = mutableListOf<SignalEnvelope>()
        var newerGeneration: Long? = null
        var foreignConnection: String? = null
        var armAck: Boolean = false
    }

    private fun receiveLocked(body: SignalEnvelope): Outcome? {
        if (isClosed) return null
        val outcome = Outcome()

        val incoming = body.gen
        if (incoming != null) {
            if (incoming < gen) {
                // An older generation is talking to a connection that no longer
                // exists. Telling it our generation is the whole repair - it
                // rebuilds upwards - and one telling per two seconds is enough,
                // since a retransmitting far end will ask again regardless.
                syncLocked()?.let { outcome.transmit += it }
                return outcome
            }
            if (incoming > gen) {
                // Not acknowledged on purpose: this channel cannot honour it,
                // and an acknowledgement would stop the far end asking before
                // anything had adopted it.
                outcome.newerGeneration = incoming
                return outcome
            }
        }

        // Addressed to a connection this side has already replaced. Its seq
        // space is not ours, so applying it would corrupt the ordered stream.
        val addressed = body.peerConn
        if (addressed != null && addressed != conn) return outcome

        val from = body.conn
        if (from != null) {
            if (peerConnection == null) {
                peerConnection = from
            } else if (from != peerConnection) {
                outcome.foreignConnection = from
                return outcome
            }
        }

        body.ack?.let { ackUpToLocked(it) }
        // A bare acknowledgement carries nothing else and is never sequenced,
        // and a `sync` carries a generation only - an equal or older one says
        // nothing this side does not already know.
        if (body.type == SignalType.ACK || body.type == SignalType.SYNC) return outcome
        if (body.type == SignalType.HEALTH) {
            outcome.deliver += body
            return outcome
        }

        val seq = body.seq
        if (seq == null) {
            // A profile-2 offer, answer or ice always carries a seq; one that
            // does not is a profile-1 shaped signal, which the negotiator
            // downgrades the pair for. Passing it through unsequenced is the
            // tolerant reading and keeps this class from being the thing that
            // drops a legacy peer's negotiation.
            outcome.deliver += body
            return outcome
        }

        // A batch stands in for every seq from `first` to `seq`, which is the
        // whole reason `first` is on the wire: the signals it coalesces were
        // sent once each and will never be sent again individually, so a
        // receiver that only looked at `seq` would hold the batch behind a gap
        // that nothing was ever going to fill. It is released as soon as its
        // range reaches what this side is waiting for.
        val first = body.first
        if (first != null && first in 1..seq) {
            if (seq < expected) {
                if (expected - 1 > 0) outcome.transmit += stampLocked(bare(SignalType.ACK))
                return outcome
            }
            if (first > expected) {
                buffer[seq] = body
                trimBufferLocked()
                return outcome
            }
            releaseLocked(body, outcome)
            drainBufferLocked(outcome)
            outcome.armAck = true
            return outcome
        }

        // A duplicate means our acknowledgement was lost - the far end would
        // not be asking again otherwise - so the repair is to acknowledge at
        // once rather than wait out the delay and watch it ask a third time.
        if (seq < expected || buffer.containsKey(seq)) {
            if (expected - 1 > 0) outcome.transmit += stampLocked(bare(SignalType.ACK))
            return outcome
        }

        if (seq > expected) {
            buffer[seq] = body
            trimBufferLocked()
            // Deliberately not acknowledged: the acknowledgement is cumulative,
            // so acknowledging a gap would claim the missing signal arrived.
            // The far end keeps retransmitting the hole, which is what is
            // wanted.
            return outcome
        }

        releaseLocked(body, outcome)
        drainBufferLocked(outcome)
        outcome.armAck = true
        return outcome
    }

    /** Release everything the buffer holds that is now next in turn. */
    private fun drainBufferLocked(outcome: Outcome) {
        while (true) {
            val next = buffer.entries.firstOrNull { (seq, body) ->
                seq == expected || (body.first?.let { it <= expected && seq >= expected } == true)
            } ?: break
            buffer.remove(next.key)
            releaseLocked(next.value, outcome)
        }
    }

    private fun trimBufferLocked() {
        while (buffer.size > timing.bufferLimit) {
            val oldest = buffer.keys.firstOrNull() ?: break
            buffer.remove(oldest)
        }
    }

    /**
     * In-order, deduplicated, and consumed rather than delivered where section
     * 3.2 says so. [expected] advances either way: a signal this side chose not
     * to act on has still arrived, and the stream must not stall on it.
     */
    private fun releaseLocked(body: SignalEnvelope, outcome: Outcome) {
        expected = (body.seq ?: return) + 1
        if (body.type == SignalType.ANSWER) {
            val re = body.re
            // An answer with no `re` is applied: that is a peer that does not
            // speak the sequenced profile, and refusing it would break the pair
            // outright. One that names an offer this side is no longer waiting
            // on is acknowledged all the same - it did arrive - and then
            // ignored, because applying it would put the connection into a
            // session neither side described.
            if (re != null && re != outstanding) return
            if (re != null) {
                // The answer is proof its offer arrived, whatever the
                // acknowledgement said.
                ackUpToLocked(re)
                outstanding = null
            }
        }
        outcome.deliver += body
    }

    private fun ackUpToLocked(n: Long) {
        if (queue.isEmpty()) return
        val before = queue.size
        queue = queue.filter { it.seq > n }.toMutableList()
        if (queue.size == before) return
        // [outstanding] is deliberately left alone. An answer normally
        // piggybacks the acknowledgement for the very offer it answers, and
        // clearing the outstanding seq here would make that answer fail its own
        // `re` check a line later - the pair would then never hear an answer at
        // all.
        if (queue.isEmpty()) idleLocked()
    }

    private fun idleLocked() {
        attempt = 0
        retryJob?.cancel()
        retryJob = null
    }

    // ------------------------------------------------------------------ send

    /**
     * Stamp the channel's identity and whatever is owed on a body.
     *
     * Every send is an acknowledgement opportunity, which is why the common
     * case costs no `ack` signal at all.
     */
    private fun stampLocked(body: SignalEnvelope): SignalEnvelope {
        val acked = expected - 1
        if (acked > 0) {
            ackJob?.cancel()
            ackJob = null
        }
        return body.copy(
            gen = gen,
            conn = conn,
            peerConn = peerConnection,
            ack = if (acked > 0) acked else null,
        )
    }

    private fun bare(type: String) = SignalEnvelope(toDevice = toDevice, type = type, roomId = roomId)

    private fun syncLocked(): SignalEnvelope? {
        val at = now()
        val last = lastSyncAt
        if (last != null && at - last < timing.syncMs) return null
        lastSyncAt = at
        // No `peerConn`: the connection it is addressed to is precisely the one
        // that no longer exists.
        return bare(SignalType.SYNC).copy(gen = gen, conn = conn)
    }

    private fun armAck(): Unit = synchronized(lock) {
        if (isClosed || ackJob?.isActive == true || expected - 1 <= 0) return
        val wait = timing.ackDelayMs
        ackJob = scope.launch {
            delay(wait)
            val envelope = synchronized(lock) {
                ackJob = null
                if (isClosed || expected - 1 <= 0) return@launch
                stampLocked(bare(SignalType.ACK))
            }
            transmit(envelope)
        }
    }

    private fun armRetry(): Unit = synchronized(lock) {
        if (isClosed || queue.isEmpty() || retryJob?.isActive == true) return
        val wait = backoffMsLocked()
        retryJob = scope.launch {
            delay(wait)
            val outgoing = synchronized(lock) {
                retryJob = null
                if (isClosed || queue.isEmpty()) return@launch
                attempt += 1
                coalesceLocked()
            }
            for (envelope in outgoing) transmit(envelope)
            armRetry()
        }
    }

    /** 1s, 2s, 4s, 8s, then 8s for ever, each spread by +/- the jitter. */
    private fun backoffMsLocked(): Long {
        val doublings = min(attempt, 30)
        val step = min(timing.intervalMs shl doublings, timing.maxIntervalMs)
        val spread = 1 + (random() * 2 - 1) * timing.jitter
        return max(1L, (step * spread).roundToLong())
    }

    /**
     * Republish the unacknowledged queue, coalesced (amendment A2).
     *
     * An offer or answer goes out as the connection's current local description
     * under its original seq, because that description already contains every
     * candidate gathered so far - re-sending the candidates beside it would be
     * redundant as well as expensive. Everything still unacknowledged that was
     * a candidate leaves as one `ice` covering `first`..`seq`, so a burst of
     * thirty trickled candidates costs one signal, not thirty.
     */
    private fun coalesceLocked(): List<SignalEnvelope> {
        val outgoing = mutableListOf<SignalEnvelope>()
        val candidates = mutableListOf<String>()
        var firstIce: Long? = null
        var lastIce: Long? = null

        for (entry in queue) {
            if (entry.body.type == SignalType.ICE) {
                if (firstIce == null) firstIce = entry.seq
                lastIce = entry.seq
                val batched = entry.body.candidates
                if (batched != null) candidates += batched
                else entry.body.candidate?.let { candidates += it }
                continue
            }
            outgoing += stampLocked(currentLocked(entry.body))
        }

        val from = firstIce
        val to = lastIce
        if (from != null && to != null) {
            // The single-candidate field is dropped on purpose: a batch that
            // also carried `candidate` would have one of its candidates applied
            // twice by a reader that understood both. Only the candidate string
            // travels, which is what max-bundle lets Android rely on (section
            // 6 step 6).
            outgoing += stampLocked(
                bare(SignalType.ICE).copy(first = from, seq = to, candidates = candidates.toList()),
            )
        }
        return outgoing
    }

    /** An offer or answer re-sent as what the connection holds now. */
    private fun currentLocked(body: SignalEnvelope): SignalEnvelope {
        if (body.type != SignalType.OFFER && body.type != SignalType.ANSWER) return body
        val sdp = localDescription?.invoke(body.type) ?: return body
        if (sdp == body.sdp) return body
        return body.copy(sdp = sdp)
    }
}
