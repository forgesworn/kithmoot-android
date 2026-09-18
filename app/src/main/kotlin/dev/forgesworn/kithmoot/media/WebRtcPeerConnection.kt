package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real peer connection, behind the interface the negotiation machine is
 * written against.
 *
 * Everything here is mechanical: turning libwebrtc's four-method callback
 * observer into suspending functions, and mapping two enums. All the judgement -
 * who gives way in a collision, what to do with a candidate that arrived early -
 * lives in [PeerLink], where it can be tested without any of this.
 */
class WebRtcPeerConnection(private val connection: PeerConnection, private val onRemoteApplied: () -> Unit = {}) : PeerConnectionHandle {

    override fun signalingState(): SignalingState = when (connection.signalingState()) {
        PeerConnection.SignalingState.STABLE -> SignalingState.STABLE
        PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> SignalingState.HAVE_LOCAL_OFFER
        PeerConnection.SignalingState.HAVE_REMOTE_OFFER -> SignalingState.HAVE_REMOTE_OFFER
        PeerConnection.SignalingState.HAVE_LOCAL_PRANSWER -> SignalingState.HAVE_LOCAL_PRANSWER
        PeerConnection.SignalingState.HAVE_REMOTE_PRANSWER -> SignalingState.HAVE_REMOTE_PRANSWER
        PeerConnection.SignalingState.CLOSED, null -> SignalingState.CLOSED
    }

    override suspend fun setLocalDescription(): SdpData {
        awaitSet { observer -> connection.setLocalDescription(observer) }
        val local = connection.localDescription ?: throw IllegalStateException("no local description after setting one")
        return SdpData(local.type.canonicalForm(), local.description)
    }

    override suspend fun setRemoteDescription(sdp: SdpData) {
        val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(sdp.type), sdp.sdp)
        awaitSet { observer -> connection.setRemoteDescription(observer, description) }
        onRemoteApplied()
    }

    override suspend fun rollbackLocalDescription() {
        val rollback = SessionDescription(SessionDescription.Type.ROLLBACK, "")
        awaitSet { observer -> connection.setLocalDescription(observer, rollback) }
    }

    override suspend fun addIceCandidate(candidate: IceCandidateData) {
        // Preserve the browser's media-section identifiers, including nonzero
        // indices when audio, camera and screen sharing negotiate together.
        val added = connection.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
        )
        if (!added) throw IllegalStateException("the candidate was refused")
    }

    override fun addSlotTransceiver(kind: SlotKind) {
        val type = when (kind) {
            SlotKind.AUDIO -> MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            SlotKind.VIDEO -> MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        }
        // A single stream id for everything this device sends, exactly as the
        // add-a-track path uses, so a receiver can still tell one device's
        // media from another's.
        connection.addTransceiver(
            type,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                listOf(STREAM_ID),
            ),
        )
    }

    override fun setSlotDirection(mid: String): Boolean =
        withTransceiver(mid) { it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV; true }

    override fun setSlotTrack(mid: String, media: Any?): Boolean {
        // The one cast in the slot machine, and it lives here because this is
        // the mechanical layer. A track that is not a native one cannot be
        // sent, and saying so marks the slot broken rather than pretending.
        if (media != null && media !is MediaStreamTrack) return false
        // `takeOwnership = false`: the track belongs to LocalMedia and outlives
        // any one connection, so the sender must not dispose it.
        return withTransceiver(mid) { it.sender.setTrack(media as MediaStreamTrack?, false) }
    }

    /**
     * Do one thing with the transceiver at this mid, and never hold it.
     *
     * `getTransceivers()` disposes every Java wrapper a previous read returned,
     * including the ones `addTransceiver` handed back, so a wrapper kept across
     * calls is a use-after-free waiting for the next remote track event. Every
     * native object's life begins and ends inside this method.
     */
    private fun withTransceiver(mid: String, action: (RtpTransceiver) -> Boolean): Boolean = runCatching {
        val transceiver = connection.transceivers.firstOrNull { it.mid == mid } ?: return false
        action(transceiver)
    }.getOrDefault(false)

    override fun localDescription(): SdpData? = runCatching {
        connection.localDescription?.let { SdpData(it.type.canonicalForm(), it.description) }
    }.getOrNull()

    override fun restartIce(): Boolean = runCatching { connection.restartIce(); true }.getOrDefault(false)

    override fun transceivers(): List<String> =
        runCatching { connection.transceivers.mapNotNull { it.mid } }.getOrDefault(emptyList())

    /**
     * One statistics report, reduced to the two counters the ladder reads.
     *
     * Per media section by `mid`, which libwebrtc puts on both `inbound-rtp`
     * and `remote-inbound-rtp`; a report that omits it is skipped rather than
     * guessed at, because attributing a counter to the wrong slot would have
     * the ladder rebuild a connection that was working.
     *
     * Inbound progress is `framesDecoded` for video and `packetsReceived` for
     * audio - packets alone would count a stream that is arriving and failing
     * to decode as healthy. Outbound is `remote-inbound-rtp`: the far end's own
     * report of what it is getting from us, which is the feedback path amendment
     * A3 chose, and `roundTripTimeMeasurements` is the field that advances only
     * while it is genuinely hearing us.
     */
    override suspend fun getStats(): List<RtpProgress> = suspendCancellableCoroutine { continuation ->
        connection.getStats { report ->
            val progress = report.statsMap.values.mapNotNull { stats ->
                val mid = stats.members["mid"] as? String ?: return@mapNotNull null
                when (stats.type) {
                    "inbound-rtp" -> {
                        val frames = number(stats.members["framesDecoded"])
                        val counter = frames ?: number(stats.members["packetsReceived"]) ?: return@mapNotNull null
                        RtpProgress(mid, RtpFlow.INBOUND, counter)
                    }
                    "remote-inbound-rtp" -> {
                        val counter = number(stats.members["roundTripTimeMeasurements"])
                            ?: number(stats.members["packetsReceived"])
                            ?: stats.timestampUs.toLong()
                        RtpProgress(mid, RtpFlow.REMOTE_INBOUND, counter)
                    }
                    else -> null
                }
            }
            continuation.resume(progress)
        }
    }

    /** libwebrtc hands counters back as whichever boxed number the native side
     *  chose, so every one of them is read the same way. */
    private fun number(value: Any?): Long? = (value as? Number)?.toLong()

    override fun close() {
        runCatching { connection.close() }
        runCatching { connection.dispose() }
    }

    private suspend fun awaitSet(action: (SdpObserver) -> Unit) = suspendCancellableCoroutine { continuation ->
        action(
            object : SdpObserver {
                override fun onSetSuccess() {
                    continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "set failed"))
                }

                // Only reached by the create-then-set form, which is not used
                // here: the implicit setLocalDescription does both at once,
                // which closes the window where the state changes in between.
                override fun onCreateSuccess(description: SessionDescription?) = Unit

                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(IllegalStateException(error ?: "create failed"))
                }
            },
        )
    }
}
