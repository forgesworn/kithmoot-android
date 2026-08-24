package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.crypto.normaliseHex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The signal body `type` values KithMoot puts on the wire. */
object SignalType {
    const val OFFER: String = "offer"
    const val ANSWER: String = "answer"

    /** A trickled candidate. Named `ice` on the wire, not `candidate`. */
    const val ICE: String = "ice"
}

/** A session description, in the two fields the wire carries. */
data class SdpData(val type: String, val sdp: String)

/**
 * A trickled candidate.
 *
 * The wire carries **only the candidate string** - there is no `sdpMid` and no
 * `sdpMLineIndex` anywhere in the signal body, and the vectors pin it that way.
 * That is workable only because every peer connection here is negotiated with
 * max-bundle and required rtcp-mux, so there is exactly one transport and every
 * candidate belongs to it. If the wire format ever grows a second transport,
 * this is the first thing that breaks.
 */
data class IceCandidateData(val candidate: String) {
    /** The m-line a candidate is attached to locally. Always the bundled one. */
    val sdpMLineIndex: Int get() = 0
    val sdpMid: String get() = ""
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

    suspend fun onNegotiationNeeded() = operations.withLock {
        try {
            makingOffer = true
            val local = connection.setLocalDescription()
            send(SignalEnvelope(remoteDevice, SignalType.OFFER, roomId, sdp = local.sdp))
        } finally {
            makingOffer = false
        }
    }

    suspend fun onLocalCandidate(candidate: IceCandidateData) {
        send(SignalEnvelope(remoteDevice, SignalType.ICE, roomId, candidate = candidate.candidate))
    }

    /** One inbound signal from the remote device. Queued behind whatever this
     *  link is already doing - see [operations]. */
    suspend fun onRemoteSignal(type: String, sdp: String?, candidate: String?) = operations.withLock {
        when (type) {
            SignalType.OFFER, SignalType.ANSWER -> if (sdp != null) onRemoteDescription(SdpData(type, sdp))
            SignalType.ICE -> if (candidate != null) onRemoteCandidate(IceCandidateData(candidate))
            else -> Unit
        }
    }

    private suspend fun onRemoteDescription(description: SdpData) {
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
            val answer = connection.setLocalDescription()
            send(SignalEnvelope(remoteDevice, SignalType.ANSWER, roomId, sdp = answer.sdp))
        }

        flushCandidates()
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

/** One outbound signal: what to send, and which device to send it to. */
data class SignalEnvelope(
    val toDevice: String,
    val type: String,
    val roomId: String,
    val sdp: String? = null,
    val candidate: String? = null,
)
