package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
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
class WebRtcPeerConnection(private val connection: PeerConnection) : PeerConnectionHandle {

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
    }

    override suspend fun rollbackLocalDescription() {
        val rollback = SessionDescription(SessionDescription.Type.ROLLBACK, "")
        awaitSet { observer -> connection.setLocalDescription(observer, rollback) }
    }

    override suspend fun addIceCandidate(candidate: IceCandidateData) {
        // sdpMid is empty and the m-line index is zero because the wire carries
        // only the candidate string. Every connection here is negotiated
        // max-bundle, so there is one transport and this is it.
        val added = connection.addIceCandidate(
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
        )
        if (!added) throw IllegalStateException("the candidate was refused")
    }

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
