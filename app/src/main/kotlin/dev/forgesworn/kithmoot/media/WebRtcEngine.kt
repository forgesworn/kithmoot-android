package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** A remote track, with the device that published it. */
data class RemoteTrack(val device: String, val track: MediaStreamTrack) {
    val trackId: String get() = track.id()
}

/**
 * The media half of a room: one peer connection per remote **device**.
 *
 * Per device, not per person. Presence groups a person's devices into one
 * participant, but their laptop's camera and their phone's screen share are two
 * separate streams from two separate machines and there is nothing to be gained
 * by pretending otherwise. What the grouping buys is in the interface, not here.
 */
class WebRtcEngine(
    private val context: Context,
    private val session: RoomSession,
    private val scope: CoroutineScope,
    private val iceServers: List<PeerConnection.IceServer>,
) {

    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory
    val localMedia: LocalMedia

    private val mutex = Mutex()
    private val links = mutableMapOf<String, ManagedLink>()

    private val _remoteTracks = MutableStateFlow<List<RemoteTrack>>(emptyList())

    /** Every track arriving from every remote device, keyed by the device that sent it. */
    val remoteTracks: StateFlow<List<RemoteTrack>> = _remoteTracks.asStateFlow()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        val audioDevice = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        localMedia = LocalMedia(context.applicationContext, factory, eglBase)
    }

    fun start() {
        // The set of devices to connect to is derived from the roster, so a
        // device that joins, leaves or lapses is reconciled here rather than
        // being handled as an event.
        scope.launch { session.remoteDevices.collect { reconcile(it) } }
        scope.launch {
            session.signals.collect { signal ->
                linkFor(signal.from)?.onRemoteSignal(
                    type = signal.body.type,
                    sdp = signal.body.sdp,
                    candidate = signal.body.candidate,
                )
            }
        }
        scope.launch { localMedia.tracks.collect { onLocalTracksChanged(it) } }
    }

    /** Tears down every connection and every capturer. */
    fun stop() {
        scope.launch {
            mutex.withLock {
                for (link in links.values) link.close()
                links.clear()
            }
            _remoteTracks.value = emptyList()
        }
        localMedia.releaseAll()
    }

    fun dispose() {
        localMedia.releaseAll()
        runCatching { factory.dispose() }
        runCatching { eglBase.release() }
    }

    private suspend fun reconcile(devices: Set<String>) = mutex.withLock {
        for (device in devices - links.keys) links[device] = openLink(device)
        for (device in links.keys - devices) {
            links.remove(device)?.close()
            _remoteTracks.value = _remoteTracks.value.filterNot { it.device == device }
        }
    }

    private suspend fun linkFor(device: String): PeerLink? = mutex.withLock { links[device]?.link }

    private fun openLink(device: String): ManagedLink {
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Max-bundle with required rtcp-mux means one transport for the
            // whole connection, which is what lets a trickled candidate travel
            // as a bare string with no m-line index attached.
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Rollback is done explicitly in PeerLink, where it is tested.
            enableImplicitRollback = false
        }

        val managed = ManagedLink(device)
        val connection = factory.createPeerConnection(configuration, managed.observer)
            ?: throw IllegalStateException("could not create a peer connection to $device")
        managed.attach(connection)
        for (track in localMedia.tracks.value) managed.addLocalTrack(track)
        return managed
    }

    private suspend fun onLocalTracksChanged(tracks: List<LocalTrack>) {
        // Tell the room what we are publishing, so a receiver can map an
        // incoming WebRTC track back to the role we said it was for.
        session.setTracks(tracks.map { TrackRef(it.trackId, it.role) })
        mutex.withLock {
            for (link in links.values) link.syncLocalTracks(tracks)
        }
    }

    /**
     * One peer connection, its negotiation machine, and the senders we have
     * added to it.
     */
    private inner class ManagedLink(private val device: String) {

        private var connection: PeerConnection? = null
        private val senders = mutableMapOf<String, RtpSender>()
        lateinit var link: PeerLink
            private set

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                val sdp = candidate?.sdp ?: return
                scope.launch { link.onLocalCandidate(IceCandidateData(sdp)) }
            }

            override fun onRenegotiationNeeded() {
                scope.launch { runCatching { link.onNegotiationNeeded() } }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                _remoteTracks.value = _remoteTracks.value + RemoteTrack(device, track)
            }

            override fun onRemoveTrack(receiver: RtpReceiver?) {
                val id = receiver?.track()?.id() ?: return
                _remoteTracks.value = _remoteTracks.value.filterNot { it.device == device && it.trackId == id }
            }

            override fun onTrack(transceiver: RtpTransceiver?) = Unit
        }

        fun attach(connection: PeerConnection) {
            this.connection = connection
            link = PeerLink(
                localDevice = session.identity.devicePubkey,
                remoteDevice = device,
                connection = WebRtcPeerConnection(connection),
                roomId = session.room.roomId,
                send = { envelope ->
                    session.sendSignal(
                        toDevice = envelope.toDevice,
                        body = SignalBody(
                            type = envelope.type,
                            roomId = envelope.roomId,
                            sdp = envelope.sdp,
                            candidate = envelope.candidate,
                        ),
                    )
                },
            )
        }

        fun addLocalTrack(track: LocalTrack) {
            val connection = connection ?: return
            if (senders.containsKey(track.trackId)) return
            // A single stream id for everything this device sends, so a receiver
            // can tell one device's tracks from another's even before the roster
            // catches up.
            runCatching { connection.addTrack(track.track, listOf(STREAM_ID)) }
                .getOrNull()
                ?.let { senders[track.trackId] = it }
        }

        fun syncLocalTracks(tracks: List<LocalTrack>) {
            val wanted = tracks.associateBy { it.trackId }
            for (track in tracks) addLocalTrack(track)
            for (id in senders.keys.toList() - wanted.keys) {
                senders.remove(id)?.let { sender -> runCatching { connection?.removeTrack(sender) } }
            }
        }

        fun close() {
            senders.clear()
            if (::link.isInitialized) link.close() else connection?.let { runCatching { it.dispose() } }
            connection = null
        }
    }

    private companion object {
        const val STREAM_ID = "kithmoot"
    }
}

/** Dispatcher the engine's own work runs on. WebRTC callbacks arrive on their own threads. */
internal val MediaDispatcher = Dispatchers.Default

/** Convenience for the audio path: mute without tearing the track down. */
fun AudioTrack.mute(muted: Boolean) {
    setEnabled(!muted)
}

/** Convenience for the video path: pause without tearing the capturer down. */
fun VideoTrack.pause(paused: Boolean) {
    setEnabled(!paused)
}
