package dev.forgesworn.kithmoot.media

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The relay check: one relay-only connection with the selected relay's login,
 * waiting up to 12 seconds for a relay candidate. That proves the endpoint
 * and the TURN login with one allocation, though not a full data path. No
 * camera or microphone is opened, and nothing about the relay is logged.
 */
internal object PublicRelayCheck {
    const val TIMEOUT_MS = 12_000L

    /** True when [candidate] (an SDP `candidate:` line) is a relay candidate. */
    internal fun isRelayCandidate(candidate: String): Boolean =
        candidate.split(' ').let { parts -> parts.indexOf("typ").let { at -> at >= 0 && parts.getOrNull(at + 1) == "relay" } }

    suspend fun run(context: Context, relay: PinnedPublicRelay): Boolean = withContext(Dispatchers.Default) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
        val found = CompletableDeferred<Boolean>()
        val configuration = PeerConnection.RTCConfiguration(CallIceServers.resolveDiscovered(relay, null, relayOnly = true)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null && isRelayCandidate(candidate.sdp)) found.complete(true)
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) found.complete(false)
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }
        val connection = factory.createPeerConnection(configuration, observer)
        var channel: DataChannel? = null
        try {
            if (connection == null) return@withContext false
            // A data channel gives the offer one transport to gather for.
            channel = connection.createDataChannel("check", DataChannel.Init())
            connection.createOffer(object : Sdp() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) { found.complete(false); return }
                    connection.setLocalDescription(object : Sdp() {
                        override fun onSetFailure(error: String?) { found.complete(false) }
                    }, description)
                }
                override fun onCreateFailure(error: String?) { found.complete(false) }
            }, MediaConstraints())
            withTimeoutOrNull(TIMEOUT_MS) { found.await() } == true
        } finally {
            runCatching { channel?.dispose() }
            runCatching { connection?.dispose() }
            runCatching { factory.dispose() }
            runCatching { audio.release() }
        }
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
