package dev.forgesworn.kithmoot.media

import android.content.Context
import android.util.Log
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
data class RemoteTrack(
    val device: String,
    val track: MediaStreamTrack,
    /** The track id as the far end advertised it in its own SDP `a=msid`,
     *  recovered per mid by `remoteTrackIds` - a receiver's own `track.id()`
     *  can differ from the sender's once libwebrtc reuses a receiver across a
     *  renegotiation. Defaults to the receiver's own id for a track built
     *  outside that path. */
    val trackId: String = track.id(),
    /** The transceiver's mid, when known. Agrees on both ends of a pair in
     *  every Unified Plan implementation, unlike the track id. */
    val mid: String? = null,
    /** True when the owning transceiver's `currentDirection` is actually
     *  receiving (`sendrecv` or `recvonly`). False is the stale-muted-receiver
     *  shape H5 describes: the far end has moved this slot on and the
     *  transceiver already says so, even though the receiver and its track
     *  linger. Defaults true so a track discovered by an older path (still
     *  possible during the transition) is not silently dropped. */
    val receiving: Boolean = true,
)

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

    /**
     * The audio stack's own native object.
     *
     * Held rather than dropped because disposing the factory does not release
     * it. It owns an `AudioRecord`, an `AudioTrack` and their threads, and only
     * [dispose] gives them back.
     */
    private val audioDevice: JavaAudioDeviceModule
    val localMedia: LocalMedia
    val audioRouting = CallAudioRouting(context)

    /**
     * Guards [links].
     *
     * A plain monitor rather than a coroutine mutex, and deliberately so:
     * teardown has to finish before the caller disposes the factory, and the
     * caller cancels this scope moments after asking for it. Anything launched
     * here would be cancelled before it ran, leaving every peer connection
     * alive behind a factory that had already gone.
     */
    private val lock = Any()
    private val links = mutableMapOf<String, ManagedLink>()

    /**
     * Which remote devices this device's media may be sent to.
     *
     * Everybody, until a caller says otherwise. A device this refuses is
     * sent an EMPTY track list, which `syncLocalTracks` turns into removed
     * senders rather than muted ones: the media never leaves this device for
     * them, which is the only version of that promise worth making. See
     * `setAudience`, and `Mesh.#audience` in the TypeScript client, which
     * this mirrors.
     */
    @Volatile
    private var audience: (String) -> Boolean = { true }

    private val _remoteTracks = MutableStateFlow<List<RemoteTrack>>(emptyList())
    private val _connections = MutableStateFlow<Map<String, String>>(emptyMap())
    val connections: StateFlow<Map<String, String>> = _connections.asStateFlow()

    /** Every track arriving from every remote device, keyed by the device that sent it. */
    val remoteTracks: StateFlow<List<RemoteTrack>> = _remoteTracks.asStateFlow()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        audioDevice = JavaAudioDeviceModule.builder(context.applicationContext)
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

    @Volatile var callActive: Boolean = true
        private set

    fun setCallActive(active: Boolean) {
        synchronized(lock) { callActive = active }
        if (active) reconcile(session.remoteDevices.value) else stop()
    }

    fun start() {
        // The set of devices to connect to is derived from the roster, so a
        // device that joins, leaves or lapses is reconciled here rather than
        // being handled as an event.
        scope.launch { session.remoteDevices.collect { reconcile(it) } }
        scope.launch {
            session.signals.collect { signal ->
                try {
                    linkFor(signal.from)?.onRemoteSignal(signal.body.type, signal.body.sdp, signal.body.candidate)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // One failed negotiation must not cancel every other device's media collectors.
                    _connections.update { it + (signal.from to "failed") }
                }
            }
        }
        scope.launch { localMedia.tracks.collect { onLocalTracksChanged(it) } }
        scope.launch {
            while (isActive) {
                delay(10_000)
                synchronized(lock) { links.values.forEach { it.reportMediaProgress() } }
            }
        }
    }

    /** Tears down every connection and every capturer. */
    fun stop() {
        synchronized(lock) { callActive = false }
        closeLinks()
        localMedia.releaseAll()
        audioRouting.close()
    }

    /**
     * Gives the native stack back. Nothing may touch this engine afterwards.
     *
     * The order is the whole point. Every peer connection has to be closed
     * before the factory that made it is disposed, and the audio device module
     * is nobody's to release but ours.
     */
    fun dispose() {
        closeLinks()
        localMedia.releaseAll()
        audioRouting.close()
        runCatching { factory.dispose() }
        runCatching { audioDevice.release() }
        runCatching { eglBase.release() }
    }

    private fun closeLinks() {
        val closing = synchronized(lock) {
            val all = links.values.toList()
            links.clear()
            all
        }
        for (link in closing) runCatching { link.close() }
        _remoteTracks.value = emptyList()
        _connections.value = emptyMap()
    }

    private fun reconcile(devices: Set<String>) = synchronized(lock) {
        if (!callActive) return@synchronized
        for (device in devices - links.keys) links[device] = openLink(device)
        for (device in links.keys - devices) {
            links.remove(device)?.close()
            _connections.update { it - device }
            _remoteTracks.update { current -> current.filterNot { it.device == device } }
        }
    }

    private fun linkFor(device: String): PeerLink? = synchronized(lock) { links[device]?.link }

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

        _connections.update { it + (device to "connecting") }
        val managed = ManagedLink(device)
        val connection = factory.createPeerConnection(configuration, managed.observer)
            ?: throw IllegalStateException("could not create a peer connection to $device")
        managed.attach(connection)
        // Judged per link rather than per publish, so a device that arrives
        // after the rule was set is judged by the same rule.
        for (track in tracksFor(device)) managed.addLocalTrack(track)
        return managed
    }

    /**
     * Say who this device's media may be sent to, and act on it now.
     *
     * A participant the rule refuses has its senders removed from the
     * connection to it - not muted, removed - so nothing further of this
     * device's camera or microphone reaches them. Called whenever the rule
     * or the roster moves.
     */
    fun setAudience(rule: (String) -> Boolean) {
        audience = rule
        val tracks = localMedia.tracks.value
        synchronized(lock) {
            for ((device, link) in links) link.syncLocalTracks(tracksFor(device, tracks))
        }
    }

    private fun tracksFor(device: String, tracks: List<LocalTrack> = localMedia.tracks.value): List<LocalTrack> =
        if (runCatching { audience(device) }.getOrDefault(false)) tracks else emptyList()

    private suspend fun onLocalTracksChanged(tracks: List<LocalTrack>) {
        // Tell the room what we are publishing, so a receiver can map an
        // incoming WebRTC track back to the role we said it was for.
        session.setTracks(tracks.map { TrackRef(it.trackId, it.role) })
        synchronized(lock) {
            for ((device, link) in links) link.syncLocalTracks(tracksFor(device, tracks))
        }
    }

    /**
     * One peer connection, its negotiation machine, and the senders we have
     * added to it.
     */
    private inner class ManagedLink(private val device: String) {

        @Volatile private var closed = false
        private var connection: PeerConnection? = null
        private val senders = mutableMapOf<String, RtpSender>()
        private val received = java.util.concurrent.ConcurrentHashMap<String, MediaStreamTrack>()
        lateinit var link: PeerLink
            private set

        val observer = object : PeerConnection.Observer {
            // currentDirection settling is driven by WebRtcPeerConnection's
            // `onRemoteApplied` hook below, which fires right after
            // setRemoteDescription - earlier and more precisely than waiting
            // for `stable`, which the answerer's own offer application never
            // reaches until it has answered. See refreshRemoteTracks.
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (!closed && state != null) _connections.update { it + (device to state.name.lowercase()) }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (!closed && state != null) _connections.update { it + (device to state.name.lowercase()) }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                val value = candidate ?: return
                scope.launch { link.onLocalCandidate(IceCandidateData(value.sdp, value.sdpMid, value.sdpMLineIndex)) }
            }

            override fun onRenegotiationNeeded() {
                scope.launch {
                    try { link.onNegotiationNeeded() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (!closed) _connections.update { it + (device to "failed") } }
                }
            }

            // Only feeds `received` - the disposal-safe store `refreshRemoteTracks`
            // reads from, see its own doc - never writes `_remoteTracks`
            // directly, so every write goes through the one place that also
            // knows mid and currentDirection.
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                received[track.id()] = track
                refreshRemoteTracks()
            }

            override fun onRemoveTrack(receiver: RtpReceiver?) {
                val id = receiver?.track()?.id() ?: return
                received.remove(id)
                refreshRemoteTracks()
            }

            // Unified Plan fires this once a transceiver's receiver has a
            // track to hand over.
            override fun onTrack(transceiver: RtpTransceiver?) = refreshRemoteTracks()
        }

        fun attach(connection: PeerConnection) {
            this.connection = connection
            link = PeerLink(
                localDevice = session.identity.devicePubkey,
                remoteDevice = device,
                connection = WebRtcPeerConnection(connection, ::refreshRemoteTracks),
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

        fun reportMediaProgress() {
            if (closed) return
            connection?.getStats { report ->
                if (closed) return@getStats
                val incoming = report.statsMap.values.filter { it.type == "inbound-rtp" }
                val summary = incoming.joinToString("; ") {
                    "${it.members["kind"] ?: it.members["mediaType"]}: packets=${it.members["packetsReceived"] ?: 0}, frames=${it.members["framesDecoded"] ?: 0}"
                }
                // No SDP, network addresses, credentials or message contents.
                val videos = _remoteTracks.value.count { it.device == device && it.track is VideoTrack }
                Log.i("KithMootMedia", "peer=${device.take(8)} state=${_connections.value[device]} videoTracks=$videos $summary")
            }
        }

        /**
         * Rebuilds this device's entries in [_remoteTracks] from the
         * connection's negotiated transceivers, resolved through
         * [remoteTracksFor].
         *
         * Two fixes to the same root problem (H5, call reliability spec
         * section 4) layered together here: [remoteTracksFor] recovers each
         * transceiver's sender-advertised track id by parsing the negotiated
         * SDP - a receiver's own `track.id()` can differ from the far end's
         * once libwebrtc reuses a receiver across a renegotiation - and reads
         * the actual [MediaStreamTrack] out of [received] rather than off a
         * fresh `connection.transceivers` snapshot, because `getTransceivers()`
         * disposes the Java wrapper objects a previous call returned. On top
         * of that, mid and `currentDirection`: a transceiver that is not
         * actually receiving is the stale-muted-receiver shape H5 describes -
         * the far end has moved this slot on and the transceiver already
         * says so, even though the receiver and its track linger.
         */
        private fun refreshRemoteTracks() {
            if (closed) return
            val pc = connection ?: return
            val bindings = remoteTracksFor(device, pc, received.values.toList())
            _remoteTracks.update { current -> current.filterNot { it.device == device } + bindings }
            val aliases = bindings.count { it.trackId != it.track.id() }
            val videos = bindings.count { it.track is VideoTrack }
            Log.i("KithMootMedia", "peer=${device.take(8)} negotiatedTracks=${bindings.size} receiverAliases=$aliases videoTracks=$videos")
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
            closed = true
            senders.clear()
            received.clear()
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

/**
 * Whether one remote device's audio track should be heard on this device
 * right now.
 *
 * Every remote audio track is judged the same way regardless of the role it
 * was advertised under in the roster: a microphone and a screen share's own
 * sound (`Roles.SCREEN_AUDIO`) are both just "incoming sound" once
 * negotiated, and not distinguishing between them here **is** how Android
 * recognises the `screen-audio` role - the same two rules already silence a
 * microphone, so they already silence a screen share's audio too.
 *
 * - Never one of this participant's own other devices: hearing your own
 *   microphone, or your own shared tab's sound, played back to you is an
 *   echo, not another person.
 * - Only while this local device is the one holding the monitor role
 *   (`listeningHere`): two of your own devices must not both speak the
 *   room to you.
 *
 * A device that has left the call needs no rule here at all: it is simply
 * absent from `remoteTracks` once [WebRtcEngine.reconcile] drops it.
 */
fun shouldPlayRemoteAudio(device: String, myDevices: Set<String>, listeningHere: Boolean): Boolean =
    listeningHere && device !in myDevices

/** Convenience for the video path: pause without tearing the capturer down. */
fun VideoTrack.pause(paused: Boolean) {
    setEnabled(!paused)
}
