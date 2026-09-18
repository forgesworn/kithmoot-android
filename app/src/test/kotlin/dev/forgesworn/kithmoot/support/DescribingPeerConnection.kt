package dev.forgesworn.kithmoot.support

import dev.forgesworn.kithmoot.media.IceCandidateData
import dev.forgesworn.kithmoot.media.PeerConnectionHandle
import dev.forgesworn.kithmoot.media.SdpData
import dev.forgesworn.kithmoot.media.SignalType
import dev.forgesworn.kithmoot.media.SignalingState

/**
 * A peer connection that describes itself the way a real one does.
 *
 * [FakePeerConnection] answers with the constant string `local-answer`, which
 * is everything the state-machine tests need and nothing the direction tests
 * do. The whole of the split-direction bug is that two descriptions of the same
 * connection differ in a way neither end compares: a microphone that reached
 * the connection between two answers changes the direction attribute, the
 * session version moves on every render, and a description re-read off the
 * connection carries every candidate gathered since. A fake whose SDP is a
 * constant cannot express any of that, and a fake whose SDP is a counter makes
 * byte equality and shape equality the same test.
 *
 * So this one carries, for one audio section:
 *
 *  - a direction derived from whether a local track is attached at the moment
 *    the description is created, narrowed against what the remote offer asked
 *    for, exactly as an answer may only narrow;
 *  - an `o=` session version that bumps on every create, so two renders of the
 *    same session are never the same bytes;
 *  - `a=candidate:` lines that accumulate, so a description re-read later is
 *    different bytes again;
 *  - `a=ice-ufrag` / `a=ice-pwd` that move only on an ICE restart.
 *
 * The state rules are [FakePeerConnection]'s, for the same reason: a fake that
 * tolerates an answer in `stable` would pass whether or not the client noticed.
 */
class DescribingPeerConnection(
    /** Names this side in its SDP, so a test failure says which end is wrong. */
    private val label: String,
) : PeerConnectionHandle {

    /**
     * Whether this device's microphone has reached this connection.
     *
     * Profile 1 adds and removes senders (`addLocalTrack` / `syncLocalTracks`
     * in `WebRtcEngine`), so "no track yet, then a track" is the ordinary
     * course of somebody joining and then unmuting - not an exotic case.
     */
    var hasLocalAudio: Boolean = false

    var state: SignalingState = SignalingState.STABLE
        private set

    val localDescriptions = mutableListOf<SdpData>()
    val remoteDescriptions = mutableListOf<SdpData>()
    val addedCandidates = mutableListOf<IceCandidateData>()

    var rollbacks: Int = 0
        private set
    var closed: Boolean = false
        private set

    /** Every `setRemoteDescription` this connection refused, and in what state.
     *  A refusal is how a real stack drops a description it cannot apply. */
    val refusals = mutableListOf<String>()

    private var sessionVersion: Long = 0
    private var iceGeneration: Int = 0
    private val candidates = mutableListOf<String>()

    private var localStable: Description? = null
    private var remoteStable: Description? = null
    private var localPending: Description? = null
    private var remotePending: Description? = null

    /** One media section's plan, before it is rendered to text. */
    private data class Description(
        val type: String,
        val label: String,
        val version: Long,
        val direction: Direction,
        val ice: Int,
    ) {
        fun render(candidates: List<String>): String = buildString {
            // A stack offers the discard port with an unspecified address until
            // it has a candidate, and rewrites all three lines the moment it
            // has one. Every retransmission is re-read off the connection, so
            // this difference is on the wire of every real call.
            val port = if (candidates.isEmpty()) 9 else 50_000 + candidates.size
            val host = if (candidates.isEmpty()) "0.0.0.0" else "198.51.100.7"
            append("v=0\r\n")
            append("o=- 4611731400430051336 $version IN IP4 127.0.0.1\r\n")
            append("s=-\r\n")
            append("t=0 0\r\n")
            append("a=group:BUNDLE 0\r\n")
            append("m=audio $port UDP/TLS/RTP/SAVPF 111\r\n")
            append("c=IN IP4 $host\r\n")
            append("a=rtcp:$port IN IP4 $host\r\n")
            append("a=rtcp-mux\r\n")
            append("a=mid:0\r\n")
            append("a=ice-ufrag:$label$ice\r\n")
            append("a=ice-pwd:${label}pwd$ice\r\n")
            append("a=rtpmap:111 opus/48000/2\r\n")
            append("a=${direction.attribute}\r\n")
            for (candidate in candidates) append("a=$candidate\r\n")
        }
    }

    enum class Direction(val attribute: String, val sends: Boolean, val receives: Boolean) {
        SENDRECV("sendrecv", true, true),
        SENDONLY("sendonly", true, false),
        RECVONLY("recvonly", false, true),
        INACTIVE("inactive", false, false),
        ;

        companion object {
            fun of(sends: Boolean, receives: Boolean): Direction = when {
                sends && receives -> SENDRECV
                sends -> SENDONLY
                receives -> RECVONLY
                else -> INACTIVE
            }

            fun parse(attribute: String): Direction =
                entries.firstOrNull { it.attribute == attribute } ?: INACTIVE
        }
    }

    /**
     * What this connection believes it is doing with audio, once both halves of
     * a negotiation have been applied.
     *
     * The intersection of the two descriptions, which is what a real stack
     * computes: this side sends only if it said it would and the far end said
     * it would receive. The bug this exists to catch is the two ends of a pair
     * disagreeing about this value while both sit in `stable` with nothing to
     * complain about.
     */
    fun negotiatedAudio(): String? {
        val local = localStable ?: return null
        val remote = remoteStable ?: return null
        return Direction.of(
            sends = local.direction.sends && remote.direction.receives,
            receives = local.direction.receives && remote.direction.sends,
        ).attribute
    }

    /** A candidate this connection gathered. It goes into every later render of
     *  the local description, which is what a retransmission re-sends. */
    fun gatherCandidate(candidate: String) {
        candidates += candidate
    }

    override fun signalingState(): SignalingState = state

    /** What this connection is sending. One microphone is all this fake has,
     *  and one is all the bug needs. */
    override fun localMedia(): Set<String>? = if (hasLocalAudio) setOf("microphone") else emptySet()

    override suspend fun setLocalDescription(): SdpData {
        sessionVersion++
        val description = when (state) {
            SignalingState.STABLE -> {
                val plan = Description(
                    type = SignalType.OFFER,
                    label = label,
                    version = sessionVersion,
                    // An offer asks for everything this side could do: it sends
                    // if it has something to send, and is always willing to
                    // receive.
                    direction = Direction.of(sends = hasLocalAudio, receives = true),
                    ice = iceGeneration,
                )
                localPending = plan
                state = SignalingState.HAVE_LOCAL_OFFER
                plan
            }

            SignalingState.HAVE_REMOTE_OFFER -> {
                val offered = checkNotNull(remotePending) { "no remote offer to answer" }
                val plan = Description(
                    type = SignalType.ANSWER,
                    label = label,
                    version = sessionVersion,
                    // An answer may only narrow: this side sends if it has a
                    // track AND the offer was willing to receive, and receives
                    // if the offer said it would send.
                    direction = Direction.of(
                        sends = hasLocalAudio && offered.direction.receives,
                        receives = offered.direction.sends,
                    ),
                    ice = iceGeneration,
                )
                // The exchange is complete the moment the answer is described.
                localStable = plan
                remoteStable = offered
                localPending = null
                remotePending = null
                state = SignalingState.STABLE
                plan
            }

            else -> throw IllegalStateException("cannot set a local description in $state")
        }
        return SdpData(description.type, description.render(candidates)).also { localDescriptions += it }
    }

    override suspend fun setRemoteDescription(sdp: SdpData) {
        val incoming = parse(sdp)
        when (sdp.type) {
            SignalType.OFFER -> {
                if (state != SignalingState.STABLE) {
                    refusals += "offer in $state"
                    throw IllegalStateException("cannot apply a remote offer in $state")
                }
                remotePending = incoming
                state = SignalingState.HAVE_REMOTE_OFFER
            }

            SignalType.ANSWER -> {
                if (state != SignalingState.HAVE_LOCAL_OFFER) {
                    // Precisely the drop at the heart of the bug: the far end's
                    // true answer reaches a connection that has already settled
                    // on a different one, and the stack refuses it.
                    refusals += "answer in $state"
                    throw IllegalStateException("cannot apply a remote answer in $state")
                }
                remoteStable = incoming
                localStable = localPending
                localPending = null
                state = SignalingState.STABLE
            }

            else -> throw IllegalStateException("unknown description type ${sdp.type}")
        }
        remoteDescriptions += sdp
    }

    override suspend fun rollbackLocalDescription() {
        rollbacks++
        localPending = null
        remotePending = null
        state = SignalingState.STABLE
    }

    override suspend fun addIceCandidate(candidate: IceCandidateData) {
        if (remoteStable == null && remotePending == null) {
            throw IllegalStateException("no remote description yet")
        }
        addedCandidates += candidate
    }

    override fun localDescription(): SdpData? {
        // What the connection holds NOW, rendered now: same session, same ICE
        // credentials, and by now carrying every candidate gathered since. This
        // is how a retransmitted description actually goes back out.
        val held = localPending ?: localStable ?: return null
        return SdpData(held.type, held.render(candidates))
    }

    override fun restartIce(): Boolean {
        if (state == SignalingState.CLOSED) return false
        iceGeneration++
        return true
    }

    override fun close() {
        closed = true
        state = SignalingState.CLOSED
    }

    /**
     * The far end's description, read back off its text.
     *
     * Reading the direction off the SDP rather than being told it is the point:
     * the wire is the only thing the two ends actually share.
     */
    private fun parse(sdp: SdpData): Description {
        val lines = sdp.sdp.split('\n').map { it.trimEnd('\r') }
        val direction = lines.firstOrNull { it.removePrefix("a=") in DIRECTIONS }
            ?.removePrefix("a=")
            ?.let(Direction::parse)
            ?: Direction.SENDRECV
        val version = lines.firstOrNull { it.startsWith("o=") }
            ?.removePrefix("o=")?.split(' ')?.getOrNull(2)?.toLongOrNull() ?: 0
        val ufrag = lines.firstOrNull { it.startsWith("a=ice-ufrag:") }?.removePrefix("a=ice-ufrag:").orEmpty()
        return Description(sdp.type, ufrag, version, direction, 0)
    }

    private companion object {
        val DIRECTIONS = setOf("sendrecv", "sendonly", "recvonly", "inactive")
    }
}
