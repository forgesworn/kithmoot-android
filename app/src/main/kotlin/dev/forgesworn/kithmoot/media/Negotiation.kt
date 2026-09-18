package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.crypto.normaliseHex
import kotlinx.serialization.json.*
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
    suspend fun openSlots(tracks: List<SlotTrack>) = operations.withLock {
        check(callProfile == 2) { "fixed media slots are profile 2 only" }
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
            send(SignalEnvelope(remoteDevice, SignalType.OFFER, roomId, sdp = local.sdp, slots = map))
        } finally {
            makingOffer = false
        }
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
        try {
            makingOffer = true
            val local = connection.setLocalDescription()
            send(SignalEnvelope(remoteDevice, SignalType.OFFER, roomId, sdp = local.sdp))
        } finally {
            makingOffer = false
        }
    }

    suspend fun onLocalCandidate(candidate: IceCandidateData) {
        send(SignalEnvelope(remoteDevice, SignalType.ICE, roomId, candidate = candidate.toWire()))
    }

    /** One inbound signal from the remote device, in the three fields profile 1
     *  carries. */
    suspend fun onRemoteSignal(type: String, sdp: String? = null, candidate: String? = null) =
        onRemoteSignal(SignalEnvelope(remoteDevice, type, roomId, sdp = sdp, candidate = candidate))

    /**
     * One inbound signal from the remote device. Queued behind whatever this
     * link is already doing - see [operations].
     *
     * An arriving body is carried in the same [SignalEnvelope] an outgoing one
     * is, so that a profile-2 field means the same thing in both directions and
     * neither side needs a second shape for it. [SignalEnvelope.toDevice] is
     * not read here: the device an inbound signal came *from* is this link's
     * own [remoteDevice], decided when the wrap was opened.
     */
    suspend fun onRemoteSignal(body: SignalEnvelope) = operations.withLock {
        when (body.type) {
            SignalType.OFFER, SignalType.ANSWER ->
                body.sdp?.let { onRemoteDescription(SdpData(body.type, it), body) }
            SignalType.ICE -> {
                val wire = body.candidates ?: listOfNotNull(body.candidate)
                for (one in wire) IceCandidateData.fromWire(one)?.let { onRemoteCandidate(it) }
            }
            else -> Unit
        }
    }

    private suspend fun onRemoteDescription(description: SdpData, body: SignalEnvelope) {
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

        settingRemoteAnswerPending = description.type == SignalType.ANSWER
        if (offerCollision) {
            // Polite by construction: an impolite collision returned above.
            // Rolling back returns us to `stable` so the remote offer can be
            // applied; without it setRemoteDescription fails and the call never
            // connects.
            connection.rollbackLocalDescription()
            collisionsResolved++
            // We are renegotiating from `stable` now. Candidates still arriving
            // belong to the description that has not landed yet, so they go
            // back to being buffered - applying them against the previous
            // description gets them refused, and a refused host candidate is a
            // call that falls back to TURN or does not connect at all.
            haveRemoteDescription = false
        }

        connection.setRemoteDescription(description)
        settingRemoteAnswerPending = false
        haveRemoteDescription = true

        // The answer comes first, and only then the buffered candidates.
        // Nothing to do with a candidate may stand between an offer and its
        // answer: an answer that is never emitted wedges the connection
        // silently for good, where a candidate that is never applied costs one
        // path out of several.
        if (description.type == SignalType.OFFER) {
            bindSlots(body)
            val answer = connection.setLocalDescription()
            send(SignalEnvelope(remoteDevice, SignalType.ANSWER, roomId, sdp = answer.sdp, re = body.seq))
        }

        flushCandidates()
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
        pendingCandidates.clear()
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
