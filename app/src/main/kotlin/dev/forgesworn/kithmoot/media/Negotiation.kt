package dev.forgesworn.kithmoot.media

import android.util.Log
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.session.CALL_PROFILE_2
import kotlinx.serialization.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The signal body `type` values KithMoot puts on the wire. */
object SignalType {
    const val OFFER: String = "offer"
    const val ANSWER: String = "answer"

    /** A trickled candidate. Named `ice` on the wire, not `candidate`. */
    const val ICE: String = "ice"

    /** Profile 2, section 2.2. A bare cumulative acknowledgement, carrying
     *  nothing but the channel's identity and how far it has received. */
    const val ACK: String = "ack"

    /** Profile 2. "My current generation is this", sent when a signal from an
     *  older generation arrives. Rate limited to one per two seconds per pair. */
    const val SYNC: String = "sync"

    /** Profile 2, section 3.4. What the sender is receiving from the
     *  recipient, per slot. Only ever reports a single dead slot on an
     *  otherwise healthy transport, which RTCP cannot express. */
    const val HEALTH: String = "health"
}

/** A session description, in the two fields the wire carries. */
data class SdpData(val type: String, val sdp: String)

/** Browser-compatible RTCIceCandidateInit, carried as JSON inside SignalBody.candidate. */
data class IceCandidateData(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val usernameFragment: String? = null,
) {
    fun toWire(): String = buildJsonObject {
        put("candidate", candidate)
        put("sdpMid", sdpMid?.let(::JsonPrimitive) ?: JsonNull)
        put("sdpMLineIndex", sdpMLineIndex)
        usernameFragment?.let { put("usernameFragment", it) }
    }.toString()

    companion object {
        fun fromWire(wire: String): IceCandidateData? = runCatching {
            require(wire.length <= 16_384)
            // Earlier Android builds sent a bare candidate; continue accepting those.
            if (wire.startsWith("candidate:")) return@runCatching IceCandidateData(wire)
            val value = Json.parseToJsonElement(wire).jsonObject
            val candidate = value.getValue("candidate").jsonPrimitive.also { require(it.isString) }.content
            require(candidate.startsWith("candidate:"))
            val mid = value["sdpMid"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
            val index = value["sdpMLineIndex"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int ?: if (mid != null) -1 else error("Missing ICE media section")
            require(index >= 0 || mid != null)
            val fragment = value["usernameFragment"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
            IceCandidateData(candidate, mid, index, fragment)
        }.getOrNull()
    }
}

/**
 * How many candidates may be held while waiting for the description they
 * belong to.
 *
 * A remote device that trickles candidates and never sends a description -
 * hostile, or simply broken - would otherwise grow this list for as long as the
 * room is open. Generous enough that a real negotiation never touches it: a
 * dual-stack host with a handful of interfaces gathers a few dozen.
 */
const val MAX_PENDING_CANDIDATES: Int = 64

/**
 * How many description shapes a connection remembers.
 *
 * Enough to cover a call's real renegotiations - a microphone, a camera, a
 * share - and small enough that a far end sending answers cannot grow it. The
 * memory is what makes a replay tellable from a disagreement; the bound is what
 * stops that memory being an attack surface.
 */
const val REMEMBERED_SHAPES: Int = 4

/**
 * How long to wait before asking again for an answer, and how many times.
 *
 * Two seconds is longer than a relay round trip and shorter than a person
 * waiting; the tail is every ten seconds for five minutes, which is longer
 * than any transient worth waiting out and short of asking for ever.
 */
val OFFER_RETRY_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L) + List(30) { 10_000L }

/**
 * Signalling, under the same logcat tag as everything else about getting into
 * a room: `adb logcat -s KithMootJoin`. Device keys are cut to eight hex
 * characters, and no SDP, candidate or room key ever goes near it - only which
 * message went which way and when.
 */
private const val NEGOTIATION_LOG = "KithMootJoin"

/**
 * How many disagreements one connection will repair before it stops.
 *
 * [REMEMBERED_SHAPES] alone is not a bound on repairs: a far end cycling more
 * shapes than the memory holds comes round again to one that has been evicted,
 * and earns another. Six is more renegotiations than a working call has, and a
 * far end that has needed seven is not converging - the answer to that is a
 * rebuild, which the health ladder owns, not an eighth offer.
 */
const val MAX_REPAIRS: Int = 6

/** The subset of `RTCSignalingState` the negotiation machine reasons about. */
enum class SignalingState { STABLE, HAVE_LOCAL_OFFER, HAVE_REMOTE_OFFER, HAVE_LOCAL_PRANSWER, HAVE_REMOTE_PRANSWER, CLOSED }

/**
 * Everything [PeerLink] needs from a peer connection, and nothing else.
 *
 * The narrowness is the point: it makes the negotiation machine - collisions,
 * rollback, candidate buffering - testable against a fake, on a plain JVM, with
 * no device and no native library. A negotiation bug that can only be
 * reproduced by putting two handsets on a desk is a bug that ships.
 */
interface PeerConnectionHandle {
    fun signalingState(): SignalingState

    /**
     * The implicit form: creates an offer in `stable` and an answer in
     * `have-remote-offer`, and applies it. Using the implicit form rather than
     * createOffer-then-setLocalDescription is what closes the window where the
     * state changes between the two calls.
     */
    suspend fun setLocalDescription(): SdpData

    suspend fun setRemoteDescription(sdp: SdpData)

    /** Explicit `setLocalDescription({type: "rollback"})`. */
    suspend fun rollbackLocalDescription()

    suspend fun addIceCandidate(candidate: IceCandidateData)

    fun close()

    // --- fixed media slots, profile 2 (call reliability spec section 3.1) ----
    //
    // Mid-keyed rather than transceiver-keyed, and every native wrapper's life
    // begins and ends inside one of these calls. See the header of
    // `media/PeerSlots.kt` for why holding one would be a use-after-free.
    //
    // Defaulted so that a connection written before any of this existed - a
    // profile-1 fake, or an adapter that has not been taught - simply cannot
    // carry slots, rather than having to say so separately.

    /**
     * Add a `sendrecv` transceiver of this kind, in this device's one stream.
     *
     * Only the side that opens a generation may call this: amendment A1.
     */
    fun addSlotTransceiver(kind: SlotKind): Unit =
        throw UnsupportedOperationException("this connection cannot carry fixed media slots")

    /**
     * Widen the transceiver at this mid to `sendrecv`.
     *
     * The answerer's one window, between applying the offer and describing the
     * answer: an answer may only ever narrow what it describes.
     */
    fun setSlotDirection(mid: String): Boolean = false

    /**
     * Swap the track a slot is sending, without renegotiating.
     *
     * `RtpSender.setTrack(track, false)`: the sender does not take ownership,
     * because the track belongs to `LocalMedia` and outlives any one
     * connection. Null empties the slot, which is how a refused audience is
     * sent nothing at all.
     */
    fun setSlotTrack(mid: String, media: Any?): Boolean = false

    // --- generations and health, profile 2 (sections 3.3 and 3.4) ------------

    /**
     * Gather again on the connection that exists.
     *
     * Inside the generation: the m-lines, their order and their mids do not
     * move, so the far end applies the offer that follows to the session it
     * already has. A rebuild at the next generation is a different act with a
     * different cost, and the health ladder is what decides to take it.
     */
    fun restartIce(): Boolean = false

    /** The media sections this connection holds, in m-line order. Named by mid,
     *  because that is the name both ends of a pair agree on. */
    fun transceivers(): List<String> = emptyList()

    /** The description this connection currently holds locally, if any. What a
     *  retransmitted offer or answer is re-sent as. */
    fun localDescription(): SdpData? = null

    /**
     * What this connection is currently sending, by track id, or null when it
     * cannot say.
     *
     * Profile 1 adds and removes senders on the connection directly, so this is
     * the only thing that can answer "would the answer come out the same?"
     * without describing the session again to find out. A connection that
     * cannot say is answered afresh and the two answers compared instead, which
     * is correct but churns the connection - so a real one should say.
     */
    fun localMedia(): Set<String>? = null

    /**
     * One sample of the counters section 3.4 reads.
     *
     * Whether media is moving is the only honest evidence that a direction is
     * alive: a connection can be `connected`, the signalling quiet, every object
     * healthy, and one direction carrying nothing at all.
     */
    suspend fun getStats(): List<RtpProgress> = emptyList()
}

/**
 * Perfect negotiation for one peer connection to one remote **device**.
 *
 * Two devices that decide to renegotiate at the same instant - which happens
 * every time two people unmute together - would otherwise deadlock, each
 * sitting in `have-local-offer` refusing the other's offer. Perfect negotiation
 * breaks the symmetry in advance: one side is polite and gives way, the other is
 * impolite and ignores the collision.
 *
 * Politeness is decided by comparing device pubkeys, which every side can do
 * without asking anyone. There is no negotiation about who negotiates.
 */
class PeerLink(
    localDevice: String,
    remoteDevice: String,
    private val connection: PeerConnectionHandle,
    private val roomId: String,
    private val send: suspend (SignalEnvelope) -> Unit,
    /**
     * 1 or 2, decided per pair and never changed after construction.
     *
     * Profile 2 is fixed media slots (section 3.1): four `sendrecv`
     * transceivers for the life of the connection, and every media change a
     * `setTrack` rather than a negotiation. A pair is profile 2 only when both
     * ends say so in the roster, so an old far end - including every Android
     * build before this one - keeps the add-and-remove path it has always had.
     */
    val callProfile: Int = 1,
    /** Where the reliable channel's retransmission timers live. Profile 2
     *  only, and required there. */
    private val scope: CoroutineScope? = null,
    /**
     * This connection has to be thrown away and a new one opened.
     *
     * Only the caller can: it owns the factory. `open` says whether the new
     * link should open a generation of its own, or wait and answer - a higher
     * generation is adopted by answering the offer that announced it, which the
     * far end is still retransmitting because this side deliberately did not
     * acknowledge it.
     */
    private val onRebuild: (suspend (gen: Long, open: Boolean) -> Unit)? = null,
    /**
     * A profile-1 shaped signal arrived from a device believed to speak profile
     * 2 - which is what a far end reloading into an old build looks like
     * (section 2.3). Nothing here can serve it: it has no generation to belong
     * to and no connection id to be addressed at.
     */
    private val onDowngrade: (suspend () -> Unit)? = null,
    /** A `health` signal arrived: what the far end is receiving from us. */
    private val onHealthSignal: (suspend (Map<String, String>) -> Unit)? = null,
    /** Defaults are the spec's; tests shorten them. */
    private val signalRetry: SignalRetryTiming = SignalRetryTiming(),
    /** Test seam: where connection instance ids come from. */
    private val newConnectionId: () -> String = ::randomConnectionId,
) {

    /**
     * Normalised here, once, at the point a device pubkey enters WebRTC
     * negotiation - see [normaliseHex]. This decides politeness below, and
     * the two sides of a connection MUST land on opposite answers: a case
     * difference that made both sides agree would collide two offers and
     * wedge the connection for good, the exact deadlock perfect negotiation
     * exists to prevent.
     */
    val localDevice: String = localDevice.normaliseHex()
    val remoteDevice: String = remoteDevice.normaliseHex()

    /**
     * The lower pubkey is polite. Arbitrary but total, and both sides compute
     * the same answer from data they both already have.
     */
    val polite: Boolean = this.localDevice < this.remoteDevice

    /**
     * Serialises every negotiation step.
     *
     * Signals are collected in one coroutine and `onRenegotiationNeeded`
     * launches its own, so an inbound offer and our own renegotiation really do
     * overlap - which is what happens every time two people unmute together.
     * Both read and write [makingOffer], [settingRemoteAnswerPending] and
     * [haveRemoteDescription] across suspension points, so without this each
     * one judges collision, politeness and rollback from a state the other is
     * halfway through changing. Every browser reference implementation queues
     * these, and for exactly this reason.
     */
    private val operations = Mutex()

    private var makingOffer = false
    private var settingRemoteAnswerPending = false
    private var haveRemoteDescription = false

    /**
     * Candidates that arrived before the description they belong to.
     *
     * This is not an edge case. Trickle ICE exists precisely so candidates can
     * be sent before gathering finishes, and a candidate routinely overtakes the
     * offer it belongs to on a relay that is publishing to several sockets. A
     * client that drops those loses its host candidates and falls back to TURN,
     * or fails outright.
     */
    private val pendingCandidates = mutableListOf<IceCandidateData>()

    /** Counted so a test can prove the buffer is used rather than merely present. */
    var bufferedCandidateCount: Int = 0
        private set

    var collisionsResolved: Int = 0
        private set

    var offersIgnored: Int = 0
        private set

    // --- the two ends completing different negotiations (profile 1) ---------
    //
    // A pair can finish a negotiation each and disagree about the result, with
    // nothing anywhere to say so: an answer written before a microphone reached
    // the connection says `recvonly`, the same offer answered again after it
    // arrives says `sendrecv`, and whichever end keeps the first one has a
    // direction the other end does not. Both sit in `stable`, both connections
    // say `connected`, RTP arrives and is never played.
    //
    // Profile 2 cannot get here: its four slots are `sendrecv` for the life of
    // the connection and every media change is a `setTrack`, so no direction
    // can move, and its reliable channel deduplicates a retransmitted offer by
    // seq long before any of this. So all of it is gated on [splitGuard].

    /** Whether the rules below apply to this pair at all. */
    private val splitGuard: Boolean = callProfile != CALL_PROFILE_2

    /**
     * Shapes of answers from the far end that this connection has applied, or
     * has already spent a repair on.
     *
     * A remembered shape is a duplicate to be dropped; an unremembered one
     * arriving with nothing outstanding is the disagreement. Bounded, because
     * a hostile or broken far end must not be able to grow this by sending
     * answers - and four is more renegotiations than a working call has.
     */
    private val remoteAnswerShapes = ArrayDeque<String>()

    /** The shape of the remote offer this connection last applied, the exact
     *  answer bytes sent for it, that answer's shape, and the local media it
     *  was written with. What a retransmitted offer is judged against. */
    private var appliedOfferShape: String? = null
    private var sentAnswerSdp: String? = null
    private var sentAnswerShape: String? = null
    private var sentAnswerMedia: Set<String>? = null

    /** A repair renegotiation is owed, as soon as this side is idle. */
    private var repairOwed = false

    /** Asking again for an answer to the offer this side has outstanding.
     *  Volatile because [close] cancels it from whatever thread closes. */
    @Volatile
    private var offerRetry: Job? = null

    /** [close] runs on whatever thread tears the call down, holding no lock.
     *  Nothing after it may touch the connection. */
    @Volatile
    private var closed = false

    /** A retransmitted offer answered from store rather than described again. */
    var answersReplayed: Int = 0
        private set

    /** An answer this connection had already settled on, arriving again. */
    var duplicateAnswersDropped: Int = 0
        private set

    /** Disagreements noticed and renegotiated. Expected to be zero on a pair
     *  whose two ends both keep up. */
    var disagreementsRepaired: Int = 0
        private set

    /** This connection has spent [MAX_REPAIRS] and will not offer again to
     *  repair. Diagnostics: a pair that reaches this wants rebuilding. */
    var repairsExhausted: Boolean = false
        private set

    /**
     * Which offer of ours the far end is being asked to answer.
     *
     * Profile 1 had no way to tell an answer to the offer now outstanding from
     * an answer to one abandoned in a rollback minutes ago - an offer describes
     * a session, not WHICH offer it replies to - so a stale answer still in
     * flight was applied to whatever offer happened to be open, and its
     * directions became the pair's. The number is ours; a far end that echoes
     * it in `re` lets this side tell the two apart, and one that does not is
     * judged exactly as before.
     */
    private var offerSeq: Long = 0
    private var outstandingOfferSeq: Long? = null

    /** Answers to an offer this side has abandoned. */
    var staleAnswersDropped: Int = 0
        private set

    // --- fixed media slots, profile 2 ---------------------------------------

    /** The four slots of this connection, once a generation has been opened by
     *  one side or the other. Null on a profile-1 link, always. */
    var slots: SlotSet? = null
        private set

    /**
     * The generation-opening offer's map from mid to slot role.
     *
     * Held beside [slots] because a receiving track's role is read off it, and
     * on the answering side the tracks arrive while the offer is being applied -
     * before there is anything to bind the slots to.
     */
    var slotMap: Map<String, String>? = null
        private set

    /** What this device is currently publishing to this peer. */
    private var localTracks: List<SlotTrack> = emptyList()

    /**
     * Renegotiations the connection asked for that this class did not want.
     *
     * Expected to stay zero on a profile-2 connection once its generation is
     * open: every media change is a `setTrack`, which raises nothing. Anything
     * else is a code path calling `addTrack` on a slotted connection, which
     * would grow the m-lines - the risk section 9 of the spec names. Counted
     * only after the opening offer has gone out, because libwebrtc raises one
     * for the four transceivers themselves and that one is ours.
     */
    var unexpectedNegotiations: Int = 0
        private set

    // --- generations, profile 2 (section 3.3) -------------------------------

    /**
     * The generation this pair is on.
     *
     * H2 is a pair whose two ends disagree about which connection they are on,
     * and nothing on the profile-1 wire can say: an offer describes a session,
     * not *which* session. A generation is the missing sentence. Zero until one
     * is opened.
     */
    var generation: Long = 0
        private set

    /** This side's connection instance id, fresh per connection and never
     *  reused. Empty until a generation is open. */
    var connectionId: String = ""
        private set

    /** The highest generation ever seen from the far end, adopted or not. A
     *  rebuild goes above both sides' highest, so a number is never reused
     *  whatever order the two sides rebuilt in. */
    var lastRemoteGeneration: Long = 0
        private set

    /** The reliable channel for this connection: sequence numbers, cumulative
     *  acknowledgement and retransmission until acknowledged. */
    private var channel: SignalChannel? = null

    /** The seq of our outstanding offer, when it was the one that opened the
     *  generation. Together with the channel's outstanding offer it is the
     *  glare column of section 3.3's table. */
    private var openingOfferSeq: Long? = null

    private var downgraded = false

    /** How many signals are still waiting to be acknowledged. Diagnostics. */
    val queueDepth: Int get() = channel?.queueDepth ?: 0

    /**
     * Open a generation: four fixed slots, this device's tracks in them, and an
     * offer carrying the map from mid to role.
     *
     * The slots are created here and only here. An answerer that created its
     * own could not associate them with the offerer's m-lines, so the far end
     * would offer four more and the pair would carry eight for the rest of the
     * call. That is amendment A1, and it is why there is no shared "make the
     * connection" helper between this and the answering path.
     *
     * The tracks go in **after** the local description rather than before it,
     * which is where this differs from the web client. A slot is named by its
     * mid here, and a mid is not real until the description that carries it has
     * been applied. Nothing is lost by the order: `setTrack` never renegotiates,
     * and a profile-2 receiver resolves a track's role from the slot map, never
     * from the `a=msid` the offer happened to carry.
     */
    suspend fun openSlots(
        tracks: List<SlotTrack>,
        gen: Long = nextGeneration(generation, lastRemoteGeneration),
    ) = operations.withLock {
        check(callProfile == CALL_PROFILE_2) { "fixed media slots are profile 2 only" }
        if (slots != null) return@withLock
        localTracks = tracks
        val set = SlotSet.open(connection)
        try {
            makingOffer = true
            val local = connection.setLocalDescription()
            check(set.assign(local.sdp)) { "the opening offer does not describe exactly four media slots" }
            val map = checkNotNull(set.map()) { "the connection assigned no mids to its media slots" }
            slots = set
            slotMap = map
            set.apply(tracks)
            generation = gen
            connectionId = newConnectionId()
            val opened = openChannel(peerConn = null, expectedSeq = 1)
            channel = opened
            openingOfferSeq =
                opened.send(SignalEnvelope(remoteDevice, SignalType.OFFER, roomId, sdp = local.sdp, slots = map))
        } finally {
            makingOffer = false
        }
    }

    /**
     * Gather again on the connection that exists, and say so.
     *
     * Inside the generation: the m-lines, their order and their mids do not
     * move, so the far end applies this to the session it already has. A
     * rebuild at the next generation is a different act with a different cost,
     * and the health ladder is what decides to take it.
     */
    suspend fun restartIce() = operations.withLock {
        val open = channel ?: return@withLock
        if (!connection.restartIce()) return@withLock
        try {
            makingOffer = true
            val local = connection.setLocalDescription()
            open.send(
                SignalEnvelope(remoteDevice, SignalType.OFFER, roomId, sdp = local.sdp, restart = true),
            )
            // Not an opening offer: nothing was created for it, so the glare
            // that follows one is an ordinary rollback rather than A1's case.
            openingOfferSeq = null
        } finally {
            makingOffer = false
        }
        Unit
    }

    /**
     * Tell the far end which of its slots this side is receiving nothing on.
     *
     * Unreliable on purpose: what is being received *right now* is the whole
     * of a health signal's value, and a stale copy of it is worse than none.
     */
    suspend fun reportHealth(rx: Map<String, String>) {
        channel?.sendUnreliable(SignalEnvelope(remoteDevice, SignalType.HEALTH, roomId, rx = rx))
    }

    /**
     * Take a slot's track out and put it back.
     *
     * The repair for the one case RTCP cannot express: a single slot the far
     * end says it is receiving nothing on, while the transport carrying it is
     * plainly fine.
     */
    suspend fun refreshSlot(role: String) = operations.withLock {
        val set = slots ?: return@withLock
        val mid = set.midOf(role) ?: return@withLock
        val track = set.track(role) ?: return@withLock
        connection.setSlotTrack(mid, null)
        connection.setSlotTrack(mid, track.media)
        Unit
    }

    /** One sample of the counters the health ladder reads. */
    suspend fun stats(): List<RtpProgress> = connection.getStats()

    /** The relay transport came back after a half-open socket. Everything
     *  unacknowledged goes out at once rather than waiting out a backoff step
     *  armed before the wire existed. */
    suspend fun reconnected() {
        channel?.reconnected()
    }

    private fun openChannel(peerConn: String?, expectedSeq: Long): SignalChannel {
        val timers = checkNotNull(scope) { "a profile-2 link needs a scope for its retransmission timers" }
        return SignalChannel(
            gen = generation,
            conn = connectionId,
            toDevice = remoteDevice,
            roomId = roomId,
            scope = timers,
            transmit = send,
            // Delivery takes the operations lock; the channel is careful to
            // call this with none of its own held, so a negotiator that sends
            // from inside its own delivery cannot deadlock against it.
            deliver = { body -> operations.withLock { applySignal(body) } },
            peerConn = peerConn,
            expectedSeq = expectedSeq,
            onNewerGeneration = { gen, body ->
                lastRemoteGeneration = maxOf(lastRemoteGeneration, gen)
                // Only an offer can be adopted: it is the only signal carrying
                // a slot map, and without one there is nothing to bind. The
                // connection is thrown away and the new one answers the offer
                // the far end is still retransmitting - deliberately never
                // acknowledged, so it is still coming.
                if (body.type == SignalType.OFFER && body.slots != null) onRebuild?.invoke(gen, false)
            },
            onForeignConnection = { _, _ ->
                // Our generation, a connection we have never heard of, nothing
                // outstanding to explain it. Section 3.3 calls that a protocol
                // error, and going up is the only repair that cannot be argued
                // with.
                onRebuild?.invoke(nextGeneration(generation, lastRemoteGeneration), true)
            },
            // A re-sent offer or answer goes out as what the connection holds
            // NOW, under its original seq: same session, same ICE credentials,
            // and by now carrying every candidate gathered since.
            localDescription = { type -> connection.localDescription()?.takeIf { it.type == type }?.sdp },
            timing = signalRetry,
        )
    }

    /**
     * Publish this set of tracks to this peer.
     *
     * The whole of D1: a camera toggle, a share, a microphone pipeline swap and
     * an audience narrowing are all the same act, none of them is a negotiation,
     * and so none of them has an answer that can be lost.
     */
    suspend fun applyTracks(tracks: List<SlotTrack>) = operations.withLock {
        localTracks = tracks
        slots?.apply(tracks)
        Unit
    }

    suspend fun onNegotiationNeeded() = operations.withLock {
        if (callProfile == 2) {
            // Deliberately not an offer. The only negotiation a slotted
            // connection ever starts is a generation or an ICE restart, and
            // both are explicit.
            if (slots != null) unexpectedNegotiations++
            return@withLock
        }
        offerLocked()
    }

    /**
     * One ordinary renegotiation. Called with [operations] held.
     *
     * The single place a profile-1 offer is made, so that a repair goes out
     * through exactly the path a microphone being unmuted goes out through -
     * same lock, same collision rules, same politeness. A repair that took a
     * shortcut past any of that would be a second negotiation machine.
     */
    private suspend fun offerLocked() {
        val seq = if (splitGuard) ++offerSeq else null
        try {
            makingOffer = true
            val local = connection.setLocalDescription()
            outstandingOfferSeq = seq
            Log.i(NEGOTIATION_LOG, "offer sent peer=${remoteDevice.take(8)} seq=${seq ?: "-"} profile=$callProfile")
            send(SignalEnvelope(remoteDevice, SignalType.OFFER, roomId, sdp = local.sdp, seq = seq))
        } finally {
            makingOffer = false
        }
        armOfferRetry()
    }

    /**
     * Ask again for an answer, until one arrives.
     *
     * Profile 1 sends each signal exactly once: a lost offer leaves this side
     * in `have-local-offer` for good, and on the impolite side every later
     * offer from the far end is then ignored as a collision, so the pair stays
     * split for the rest of the call with neither end able to say why. That is
     * true of a repair and of an ordinary offer alike - the same wedge, from
     * the same single transmission - so every profile-1 offer is retried here
     * rather than only the repairs.
     *
     * What goes back out is what the connection holds NOW: same session, same
     * ICE credentials, and by now carrying every candidate gathered since, which
     * a far end reads as the same shape and answers from store. Bounded rather
     * than endless, because a pair that has gone unanswered for five minutes
     * has a problem no amount of asking will fix.
     */
    private fun armOfferRetry() {
        val timers = scope ?: return
        offerRetry?.cancel()
        offerRetry = timers.launch {
            for (wait in OFFER_RETRY_MS) {
                delay(jittered(wait))
                if (connection.signalingState() != SignalingState.HAVE_LOCAL_OFFER) return@launch
                val held = connection.localDescription()?.takeIf { it.type == SignalType.OFFER } ?: return@launch
                // The same offer, under the same number: asking again is not a
                // new offer, and an answer to it answers the one outstanding.
                Log.i(NEGOTIATION_LOG, "offer retry peer=${remoteDevice.take(8)} seq=${outstandingOfferSeq ?: "-"} afterMs=$wait")
                send(SignalEnvelope(remoteDevice, SignalType.OFFER, roomId, sdp = held.sdp, seq = outstandingOfferSeq))
            }
        }
    }

    /**
     * Spread across the pair.
     *
     * Two devices that lost each other's offers in the same instant would
     * otherwise ask again in the same instant, for as long as they both keep
     * asking.
     */
    private fun jittered(wait: Long): Long = wait + (Entropy.bytes(1).first().toLong() and 0xff) * wait / 1_280

    /** The offer has been answered, rolled back or given up on. */
    private fun stopOfferRetry() {
        offerRetry?.cancel()
        offerRetry = null
    }

    suspend fun onLocalCandidate(candidate: IceCandidateData) {
        val body = SignalEnvelope(remoteDevice, SignalType.ICE, roomId, candidate = candidate.toWire())
        // Through the channel on a profile-2 pair, so a candidate that is lost
        // is asked for again - batched with every other unacknowledged one, so
        // a burst of thirty costs one signal rather than thirty (amendment A2).
        val open = channel
        if (open != null) open.send(body) else send(body)
    }

    /** One inbound signal from the remote device, in the three fields profile 1
     *  carries. */
    suspend fun onRemoteSignal(type: String, sdp: String? = null, candidate: String? = null) =
        onRemoteSignal(SignalEnvelope(remoteDevice, type, roomId, sdp = sdp, candidate = candidate))

    /**
     * One inbound signal from the remote device.
     *
     * An arriving body is carried in the same [SignalEnvelope] an outgoing one
     * is, so that a profile-2 field means the same thing in both directions and
     * neither side needs a second shape for it. [SignalEnvelope.toDevice] is
     * not read here: the device an inbound signal came *from* is this link's
     * own [remoteDevice], decided when the wrap was opened.
     *
     * On a profile-2 pair this is three steps with the lock held for as little
     * of them as possible: judge the generation, hand the body to the reliable
     * channel, and let the channel deliver it back in seq order once it is sure
     * it is the next one. Profile 1 is unchanged - straight through, under the
     * lock, exactly as it has always been.
     */
    suspend fun onRemoteSignal(body: SignalEnvelope) {
        // The connection is gone. A description pushed at a closed one is
        // refused, and that refusal used to escape into the engine's catch and
        // mark a pair that had simply hung up as failed.
        if (closed) return
        if (callProfile != CALL_PROFILE_2) {
            operations.withLock { applySignal(body) }
            return
        }
        if (downgraded) return

        if (body.gen == null) {
            // Section 2.3: a profile-1 shaped signal from a device believed to
            // speak profile 2 downgrades the pair. It has no generation to
            // belong to and no connection id to be addressed at, so nothing
            // here can serve it and the caller rebuilds legacy-style.
            if (body.type !in setOf(SignalType.OFFER, SignalType.ANSWER, SignalType.ICE)) return
            downgraded = true
            onDowngrade?.invoke()
            return
        }
        lastRemoteGeneration = maxOf(lastRemoteGeneration, body.gen)

        val open = channel
        if (open == null) {
            // Nothing open yet: the far end got its offer out before this side
            // opened anything, which on this client is the ordinary case for
            // the polite half of every pair. Answering it is cheaper and far
            // more reliable than opening a generation of our own and then
            // resolving the glare.
            if (body.type == SignalType.OFFER && body.slots != null) adoptGeneration(body)
            return
        }

        if (body.type == SignalType.OFFER) {
            val action = operations.withLock {
                decideOffer(
                    GenerationInput(
                        incomingGen = body.gen,
                        currentGen = generation,
                        incomingConn = body.conn,
                        boundConn = open.peerConn,
                        outstanding = outstanding(open),
                        polite = polite,
                        opensGeneration = body.slots != null,
                    ),
                )
            }
            when (action) {
                // Impolite, and our own offer is out. Saying nothing is the
                // answer: the far end is polite and is about to give way.
                GenerationAction.Ignore -> {
                    offersIgnored++
                    return
                }
                // A1: a rollback would not release the four transceivers this
                // side opened, so only discarding the connection gives them up.
                // The new one answers the offer the far end is still asking
                // about, because this side never acknowledged it.
                GenerationAction.RebuildConnection -> {
                    onRebuild?.invoke(body.gen, false)
                    return
                }
                is GenerationAction.Adopt -> {
                    onRebuild?.invoke(action.gen, false)
                    return
                }
                is GenerationAction.RebuildGeneration -> {
                    onRebuild?.invoke(action.gen, true)
                    return
                }
                GenerationAction.Rollback -> {
                    // In-generation glare. Nothing was created for our offer,
                    // so A1's reason does not apply and an ordinary rollback is
                    // both correct and far cheaper than throwing away a
                    // connection that is carrying media.
                    val outstandingSeq = open.outstandingOffer
                    operations.withLock {
                        connection.rollbackLocalDescription()
                        collisionsResolved++
                        haveRemoteDescription = false
                    }
                    if (outstandingSeq != null) open.drop(outstandingSeq)
                    openingOfferSeq = null
                }
                // `sync` is the channel's to send, and it rate limits it;
                // `negotiate` simply falls through to the ordinary path.
                GenerationAction.Sync, GenerationAction.Negotiate -> Unit
            }
        }

        open.receive(body)
    }

    /** What this side is currently owed an answer for - the glare column of
     *  section 3.3's table. */
    private fun outstanding(open: SignalChannel): OutstandingOffer {
        val seq = open.outstandingOffer ?: return OutstandingOffer.NONE
        return if (seq == openingOfferSeq) OutstandingOffer.OPENING else OutstandingOffer.IN_GENERATION
    }

    /**
     * Answer a generation the far end opened, on a connection that has no
     * transceivers of its own.
     *
     * The channel is created first and the offer fed through it, so its seq is
     * acknowledged and ordered exactly as every later signal's is - and so the
     * far end stops retransmitting it.
     */
    private suspend fun adoptGeneration(body: SignalEnvelope) {
        val gen = body.gen ?: return
        val fresh = operations.withLock {
            if (channel != null) return@withLock null
            generation = gen
            connectionId = newConnectionId()
            openChannel(peerConn = body.conn, expectedSeq = body.seq ?: 1).also { channel = it }
        } ?: return
        fresh.receive(body)
    }

    /** One signal, in order and deduplicated, ready to be acted on. Called with
     *  [operations] held. */
    private suspend fun applySignal(body: SignalEnvelope) {
        when (body.type) {
            SignalType.OFFER, SignalType.ANSWER ->
                body.sdp?.let { onRemoteDescription(SdpData(body.type, it), body) }
            SignalType.ICE -> {
                val wire = body.candidates ?: listOfNotNull(body.candidate)
                for (one in wire) IceCandidateData.fromWire(one)?.let { onRemoteCandidate(it) }
            }
            SignalType.HEALTH -> body.rx?.let { onHealthSignal?.invoke(it) }
            else -> Unit
        }
    }

    private suspend fun onRemoteDescription(description: SdpData, body: SignalEnvelope) {
        Log.i(
            NEGOTIATION_LOG,
            "${description.type} received peer=${remoteDevice.take(8)} seq=${body.seq ?: "-"} re=${body.re ?: "-"} " +
                "state=${connection.signalingState()}",
        )
        val readyForOffer = !makingOffer &&
            (connection.signalingState() == SignalingState.STABLE || settingRemoteAnswerPending)
        val offerCollision = description.type == SignalType.OFFER && !readyForOffer

        // The impolite side simply pretends it never arrived, and keeps its own
        // offer in flight. The polite side will roll back and answer it, so
        // exactly one offer survives.
        //
        // A local, not a field: whether we ignored *this* offer governs nothing
        // beyond this call, and holding it across suspension points was one of
        // the pieces of state two overlapping coroutines used to tear.
        val ignoreOffer = !polite && offerCollision
        if (ignoreOffer) {
            offersIgnored++
            return
        }

        // An answer to an offer this side has given up on. It says nothing
        // about what the pair is doing now - applying it would hand the
        // outstanding offer a stale session's directions, and repairing from it
        // would spend a repair on a disagreement that does not exist.
        if (splitGuard && description.type == SignalType.ANSWER &&
            body.re != null && body.re != outstandingOfferSeq
        ) {
            staleAnswersDropped++
            return
        }

        // An answer for a negotiation this connection is not in. Applying it is
        // impossible - the stack refuses an answer with no offer outstanding -
        // and letting that refusal escape was how a working pair came to be
        // labelled failed. Whether it is a replay or a disagreement is decided
        // by what it says, not by the fact that it arrived.
        if (splitGuard &&
            description.type == SignalType.ANSWER &&
            connection.signalingState() != SignalingState.HAVE_LOCAL_OFFER
        ) {
            onAnswerOutOfTurn(description)
            return
        }

        // The same offer again, and nothing on this side has moved since it was
        // answered. Replaying those exact bytes is the whole repair: describing
        // the session again would mint a new answer, and a new answer is a new
        // shape the far end may already be unable to apply.
        //
        // Not after a collision: the rollback discarded an offer of our own,
        // and the connection genuinely needs describing again.
        val replay = if (splitGuard && description.type == SignalType.OFFER && !offerCollision) {
            replayable(description)
        } else {
            null
        }
        if (replay != null) {
            haveRemoteDescription = true
            answersReplayed++
            sendAnswer(replay, body)
            flushCandidates()
            return
        }

        settingRemoteAnswerPending = description.type == SignalType.ANSWER
        if (offerCollision) {
            // Polite by construction: an impolite collision returned above.
            // Rolling back returns us to `stable` so the remote offer can be
            // applied; without it setRemoteDescription fails and the call never
            // connects.
            connection.rollbackLocalDescription()
            stopOfferRetry()
            outstandingOfferSeq = null
            collisionsResolved++
            // We are renegotiating from `stable` now. Candidates still arriving
            // belong to the description that has not landed yet, so they go
            // back to being buffered - applying them against the previous
            // description gets them refused, and a refused host candidate is a
            // call that falls back to TURN or does not connect at all.
            haveRemoteDescription = false
        }

        // Judged before the description is applied, because applying it is what
        // replaces the thing being compared against.
        val repeatedOffer = splitGuard &&
            description.type == SignalType.OFFER &&
            SdpShape.of(description.sdp) == appliedOfferShape
        val previousAnswerShape = sentAnswerShape

        connection.setRemoteDescription(description)
        settingRemoteAnswerPending = false
        haveRemoteDescription = true
        if (description.type == SignalType.ANSWER) {
            stopOfferRetry()
            outstandingOfferSeq = null
        }
        if (splitGuard && description.type == SignalType.ANSWER) {
            remember(remoteAnswerShapes, SdpShape.of(description.sdp))
        }

        // The answer comes first, and only then the buffered candidates.
        // Nothing to do with a candidate may stand between an offer and its
        // answer: an answer that is never emitted wedges the connection
        // silently for good, where a candidate that is never applied costs one
        // path out of several.
        if (description.type == SignalType.OFFER) {
            bindSlots(body)
            // Read BEFORE the session is described, not after. The engine adds
            // and removes senders on the connection without this lock, so a
            // track landing in that window is not in the answer - and media
            // recorded afterwards would say it was, which is exactly the state
            // in which a later replay sends an answer that predates the
            // microphone it claims to have been written with.
            val media = if (splitGuard) connection.localMedia() else null
            val answer = connection.setLocalDescription()
            val shape = SdpShape.of(answer.sdp)
            if (splitGuard) {
                appliedOfferShape = SdpShape.of(description.sdp)
                sentAnswerSdp = answer.sdp
                sentAnswerShape = shape
                sentAnswerMedia = media
            }
            sendAnswer(answer.sdp, body)
            // The same offer, answered differently: local media moved between
            // the two, or a rollback re-described the session. The far end may
            // already have settled on the answer this one replaces and will
            // refuse it exactly as this side used to, so one offer from here is
            // the repair - and an offer can never be mistaken for a duplicate
            // answer or a repeated offer, which is what stops two fixed clients
            // repairing at each other for ever.
            if (repeatedOffer && previousAnswerShape != null && previousAnswerShape != shape) {
                repairNeeded()
            }
        }

        flushCandidates()
        repairIfOwed()
    }

    /**
     * An answer that arrived with no offer of ours outstanding.
     *
     * A shape this connection has already applied is a replay and is dropped
     * without a sound: the web client replays its stored answer on every
     * retransmitted offer by design, so on a desktop-to-Android call this is
     * the ordinary case rather than the exception. Any other shape is the far
     * end telling us, in the only way it can, that it completed a negotiation
     * this side did not.
     */
    private suspend fun onAnswerOutOfTurn(description: SdpData) {
        val shape = SdpShape.of(description.sdp)
        if (shape in remoteAnswerShapes) {
            duplicateAnswersDropped++
            return
        }
        // Remembered before the repair rather than after it, so a second copy
        // of the same disagreement costs nothing. That is not by itself a bound
        // on repairs - the memory is [REMEMBERED_SHAPES] deep, and a far end
        // cycling more shapes than that comes round again to an evicted one -
        // so [MAX_REPAIRS] is what actually stops this.
        remember(remoteAnswerShapes, shape)
        repairNeeded()
        repairIfOwed()
    }

    /**
     * Whether a repeated offer can be answered from store.
     *
     * The answer has to be one this connection actually sent, for an offer of
     * the same shape, written with the media the connection is sending now. A
     * connection that cannot say what it is sending is answered afresh, which
     * is slower but never wrong.
     */
    private fun replayable(description: SdpData): String? {
        // Read once and returned, never re-read: the caller must send the exact
        // bytes this decision was made about, whatever else changes in between.
        val stored = sentAnswerSdp ?: return null
        if (stored.isEmpty()) return null
        if (SdpShape.of(description.sdp) != (appliedOfferShape ?: return null)) return null
        val current = connection.localMedia() ?: return null
        return if (current == sentAnswerMedia) stored else null
    }

    /**
     * A disagreement, and the one offer it earns - up to a point.
     *
     * Past [MAX_REPAIRS] this connection stops offering. Repairing for ever
     * against a far end that never converges is an offer storm dressed up as a
     * fix, and the honest escalation from there is a rebuild.
     */
    private fun repairNeeded() {
        if (disagreementsRepaired >= MAX_REPAIRS) {
            repairsExhausted = true
            return
        }
        disagreementsRepaired++
        repairOwed = true
    }

    /**
     * The one repair, once this side is idle.
     *
     * A renegotiation already in flight settles the pair by itself - its answer
     * describes the session as it is now - so the owed repair is dropped rather
     * than queued behind it. Called with [operations] held.
     */
    private suspend fun repairIfOwed() {
        if (!repairOwed) return
        repairOwed = false
        if (makingOffer) return
        if (connection.signalingState() != SignalingState.STABLE) return
        offerLocked()
    }

    private suspend fun sendAnswer(sdp: String, body: SignalEnvelope) {
        val reply = SignalEnvelope(remoteDevice, SignalType.ANSWER, roomId, sdp = sdp, re = body.seq)
        Log.i(NEGOTIATION_LOG, "answer sent peer=${remoteDevice.take(8)} re=${body.seq ?: "-"}")
        // Reliably on a profile-2 pair. A lost answer was the failure that
        // left a pair blind for the rest of a call, because profile 1 sent
        // one exactly once and never again (H1).
        val open = channel
        if (open != null) open.send(reply) else send(reply)
    }

    /** Bounded, oldest first. Nothing a far end sends may grow this. */
    private fun remember(shapes: ArrayDeque<String>, shape: String) {
        if (shape in shapes) return
        shapes.addLast(shape)
        while (shapes.size > REMEMBERED_SHAPES) shapes.removeFirst()
    }

    /**
     * Adopt the transceivers the offer just created, by the map it carried.
     *
     * Between applying the offer and describing the answer, and nowhere else:
     * a remote offer creates its transceivers `recvonly`, and an answer can
     * only ever narrow what it describes, so a slot not widened here is a slot
     * this side can never send on for the life of the connection.
     */
    private fun bindSlots(body: SignalEnvelope) {
        if (callProfile != 2 || slots != null) return
        val offered = body.slots ?: return
        val set = SlotSet.bind(connection, offered) ?: return
        slots = set
        slotMap = offered
        set.apply(localTracks)
    }

    private suspend fun onRemoteCandidate(candidate: IceCandidateData) {
        if (!haveRemoteDescription) {
            pendingCandidates += candidate
            bufferedCandidateCount++
            // Bounded: see [MAX_PENDING_CANDIDATES]. The oldest goes, because
            // the newest candidate is the one most likely still to work.
            while (pendingCandidates.size > MAX_PENDING_CANDIDATES) pendingCandidates.removeAt(0)
            return
        }
        addCandidate(candidate)
    }

    private suspend fun flushCandidates() {
        if (pendingCandidates.isEmpty()) return
        val drained = pendingCandidates.toList()
        pendingCandidates.clear()
        for (candidate in drained) addCandidate(candidate)
    }

    private suspend fun addCandidate(candidate: IceCandidateData) {
        try {
            connection.addIceCandidate(candidate)
        } catch (_: Exception) {
            // An offer we deliberately ignored leaves candidates behind that
            // belong to a description we never applied. Refusing them is
            // correct, and is not a failure worth propagating into the call.
        }
    }

    fun close() {
        // The channel goes first, so nothing in flight for this connection can
        // reach whatever replaces it: a retransmission addressed to a `conn`
        // that no longer exists, or a candidate for a description nobody holds.
        // "A rebuild offer never reaches the old connection" is a property of
        // this order.
        closed = true
        stopOfferRetry()
        channel?.close()
        channel = null
        pendingCandidates.clear()
        // Deliberately NOT clearing what the negotiation rules read. This runs
        // on the thread that tears the call down, holding no lock, while a
        // signal may be halfway through being judged against exactly those
        // fields. They are bounded and this object is being discarded, so there
        // is nothing to gain by racing a lock-holder for them.
        connection.close()
    }
}

/**
 * One outbound signal: what to send, and which device to send it to.
 *
 * The fields below [candidate] are profile 2 (call reliability spec section
 * 2.2). They are declared in the order `SignalBody.toJson` writes them - `first`
 * before `seq` - so that the mapping onto the wire stays a field-for-field
 * transcription and the two clients' bytes match.
 */
data class SignalEnvelope(
    val toDevice: String,
    val type: String,
    val roomId: String,
    val sdp: String? = null,
    val candidate: String? = null,
    /** The pair generation this signal belongs to. */
    val gen: Long? = null,
    /** The sender's connection instance id, fresh per peer connection. */
    val conn: String? = null,
    /** The connection the sender believes it is addressing. */
    val peerConn: String? = null,
    /** First seq covered by a batched `ice`. */
    val first: Long? = null,
    /** Per [conn], from 1, gapless. On an offer, answer or ice. */
    val seq: Long? = null,
    /** Batched candidates. [candidate] stays on the wire for profile-1 peers. */
    val candidates: List<String>? = null,
    /** Highest contiguous seq received from [peerConn]. */
    val ack: Long? = null,
    /** Seq of the offer an answer answers. */
    val re: Long? = null,
    /** This offer carries an ICE restart inside the current generation. */
    val restart: Boolean? = null,
    /** A generation-opening offer's map from transceiver mid to slot role. */
    val slots: Map<String, String>? = null,
    /** On a `health` signal: what the sender is receiving, per role. */
    val rx: Map<String, String>? = null,
)

/**
 * A connection instance id: 16 lower-case hex, fresh per peer connection and
 * never reused (section 2.2).
 *
 * It is what lets the two ends of a pair tell "this is about the connection we
 * are both on" from "this is about one of us that no longer exists", which
 * profile 1 cannot say at all.
 */
internal fun randomConnectionId(): String =
    Entropy.bytes(8).joinToString("") { "%02x".format(it) }
