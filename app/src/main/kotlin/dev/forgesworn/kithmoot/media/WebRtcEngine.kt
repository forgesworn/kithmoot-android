package dev.forgesworn.kithmoot.media

import android.content.Context
import android.util.Log
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.session.CALL_PROFILE_2
import dev.forgesworn.kithmoot.session.CALL_PROFILE_2_ENABLED
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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
    /**
     * The slot this track arrived in, read off the generation-opening offer's
     * `slots` map by [mid].
     *
     * Only ever set on a profile-2 pair, and there it is the whole of
     * receive-side role resolution (spec section 4): a mid agrees on both ends
     * of a pair in every Unified Plan implementation, where a sender's `a=msid`
     * in a fixed slot is minted per transceiver and so never matches the track
     * id the roster advertised. Null on profile 1, where the advert is still
     * the only thing that ties a receiver to a slot.
     */
    val role: String? = null,
)

/** One RTCStats entry, reduced to what [summariseMediaProgress] reads. */
internal data class StatsEntry(val type: String, val members: Map<String, Any?>)

/**
 * The per-peer progress line for [WebRtcEngine.PeerLink.reportMediaProgress].
 *
 * `framesDecoded` only exists on video inbound-rtp; an audio line built from
 * it always reads `frames=0`, which once passed for audio not decoding.
 * Audio gets its own counters instead - packets, loss, samples and
 * concealment - plus a sender's own packetsSent so a stalled outbound leg is
 * visible too.
 */
internal fun summariseMediaProgress(entries: List<StatsEntry>): String =
    entries.mapNotNull { entry ->
        val kind = entry.members["kind"] ?: entry.members["mediaType"]
        when (entry.type) {
            "inbound-rtp" -> when (kind) {
                "audio" -> buildString {
                    append("audio: packets=${entry.members["packetsReceived"] ?: 0}")
                    append(", lost=${entry.members["packetsLost"] ?: 0}")
                    append(", samples=${entry.members["totalSamplesReceived"] ?: 0}")
                    append(", concealed=${entry.members["concealedSamples"] ?: 0}")
                    entry.members["silentConcealedSamples"]?.let { append(", silentConcealed=$it") }
                    entry.members["audioLevel"]?.let { append(", level=$it") }
                }
                "video" -> "video: frames=${entry.members["framesDecoded"] ?: 0}, lost=${entry.members["packetsLost"] ?: 0}"
                else -> "$kind: packets=${entry.members["packetsReceived"] ?: 0}"
            }
            "outbound-rtp" -> if (kind == "audio") "audioSent: packets=${entry.members["packetsSent"] ?: 0}" else null
            else -> null
        }
    }.joinToString("; ")

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

    /**
     * Where the engine's jobs run.
     *
     * A supervisor under the caller's scope, with a handler: a job that throws
     * is a logged failure, not the end of the process, and does not take the
     * other jobs with it. Cancelling the caller's scope still cancels all of
     * it. The caller's dispatcher is kept; the collectors pick
     * [MediaDispatcher] themselves.
     */
    private val engineScope = CoroutineScope(
        scope.coroutineContext +
            SupervisorJob(scope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, failure -> Log.e("KithMootMedia", "engine job failed", failure) },
    )

    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory

    /**
     * The audio stack's own native object.
     *
     * Held rather than dropped because disposing the factory does not release
     * it. It owns an `AudioRecord`, an `AudioTrack` and their threads, and only
     * [dispose] gives them back.
     */
    private val playbackAudio = PlaybackAudio()
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
     *
     * Two rules keep it from deadlocking against libwebrtc, whose callbacks
     * arrive on its signalling thread and whose `close` waits for that thread:
     * nothing that runs on a WebRTC callback takes this lock (see
     * [LinkTable]), and a link is closed only after this lock is released.
     */
    private val lock = Any()
    private val links = LinkTable<ManagedLink>()

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

    /** This device's microphone, measured on the record thread. See [SpeakingLevels.RMS]. */
    private val microphoneLevel = LevelMeter()
    private val _speakingDevices = MutableStateFlow<Set<String>>(emptySet())
    private val _selfSpeaking = MutableStateFlow(false)

    /** Remote devices whose microphone is currently carrying speech. */
    val speakingDevices: StateFlow<Set<String>> = _speakingDevices.asStateFlow()

    /** This device's own microphone is carrying speech. Not gated on mute:
     *  the caller knows whether the microphone is live and muted. */
    val selfSpeaking: StateFlow<Boolean> = _selfSpeaking.asStateFlow()

    /**
     * The playback gain this device applies to a remote device, set by the
     * caller from the per-person volume. libwebrtc measures a receiver's level
     * after that gain, so a person turned down to half would otherwise have
     * to shout to light their cue here.
     */
    @Volatile var speakingGain: (String) -> Double = { 1.0 }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        audioDevice = JavaAudioDeviceModule.builder(context.applicationContext)
            .setSampleRate(48_000)
            .setAudioBufferCallback { buffer, format, channels, rate, bytes, timestamp ->
                // Measured before any app sound is mixed in, so the speaking
                // cue follows this person's voice rather than a shared video.
                if (format == android.media.AudioFormat.ENCODING_PCM_16BIT) runCatching { microphoneLevel.addPcm16(buffer, bytes) }
                playbackAudio.fill(buffer, format, channels, rate, bytes)
                timestamp
            }
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        localMedia = LocalMedia(context.applicationContext, factory, eglBase, playbackAudio)
    }

    @Volatile var callActive: Boolean = true
        private set

    fun setCallActive(active: Boolean) {
        synchronized(lock) { callActive = active }
        if (active) engineScope.launch(MediaDispatcher) { reconcile(session.remoteDevices.value) } else stop()
    }

    /**
     * Everything here runs on [MediaDispatcher], never on the caller's thread.
     * Opening and closing a peer connection blocks until libwebrtc's signalling
     * thread has done it, and a roster change used to do that on the main
     * thread, which is where the input timeout is measured.
     */
    fun start() {
        // A working connection keeps its device in the roster through a
        // relay's silence. A plain state read: the session calls this under
        // its own lock.
        session.mediaConnected = { device -> _connections.value[device] == "connected" }
        // The set of devices to connect to is derived from the roster, so a
        // device that joins, leaves or lapses is reconciled here rather than
        // being handled as an event.
        engineScope.launch(MediaDispatcher) { session.remoteDevices.collect { reconcile(it) } }
        engineScope.launch(MediaDispatcher) {
            session.signals.collect { signal ->
                val target = managedLinkFor(signal.from) ?: return@collect
                try {
                    target.onRemoteSignal(inboundEnvelope(signal.from, signal.body))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // A signal can finish after its link was deliberately
                    // replaced. That old link's exception must not relabel the
                    // replacement as failed: publish only while this is still
                    // the device's current link.
                    if (callActive && links.isCurrent(signal.from, target)) {
                        Log.w("KithMootMedia", "signal failed peer=${signal.from.take(8)}", failure)
                        _connections.update { it + (signal.from to "failed") }
                    }
                }
            }
        }
        engineScope.launch(MediaDispatcher) { localMedia.tracks.collect { onLocalTracksChanged(it) } }
        engineScope.launch(MediaDispatcher) {
            while (isActive) {
                delay(10_000)
                synchronized(lock) { links.values().forEach { it.reportMediaProgress() } }
            }
        }
        engineScope.launch(MediaDispatcher) { pollSpeaking() }
        // One statistics sample per profile-2 pair every two seconds, which is
        // the whole input to the health ladder. Whether packets are moving is
        // the only honest evidence that a direction is alive: a connection can
        // be `connected`, the signalling quiet, every object healthy, and one
        // direction carrying nothing at all.
        engineScope.launch(MediaDispatcher) {
            while (isActive) {
                delay(HEALTH_SAMPLE_MS)
                val sampling = synchronized(lock) { links.values().filter { it.profileTwo } }
                for (link in sampling) runCatching { link.sampleHealth() }
            }
        }
    }

    /**
     * Who is speaking, a few times a second.
     *
     * Remote devices from each connection's receive statistics, this device
     * from its own record buffers; two scales, two sets of thresholds (see
     * [SpeakingLevels]). A poll that times out keeps that device's detector
     * as it was rather than reading as silence.
     */
    private suspend fun pollSpeaking() {
        val remote = SpeakingSet(SpeakingLevels.STATS)
        val self = SpeakingDetector(SpeakingLevels.RMS)
        while (kotlin.coroutines.coroutineContext.isActive) {
            delay(SPEAKING_SAMPLE_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            val sampling = synchronized(lock) { if (callActive) links.values() else emptyList() }
            for (link in sampling) {
                val level = withTimeoutOrNull(SPEAKING_SAMPLE_MS) { link.speechLevel() } ?: continue
                val gain = runCatching { speakingGain(link.device) }.getOrDefault(1.0)
                remote.update(link.device, if (gain > 0.01) level / gain else 0.0, now)
            }
            remote.retain(sampling.map { it.device }.toSet())
            _speakingDevices.value = remote.active()
            val mic = microphoneLevel.drain()
            _selfSpeaking.value = if (mic == null) { self.reset(); false } else self.update(mic, now)
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
        val closing = synchronized(lock) { links.clear() }
        for (link in closing) runCatching { link.close() }
        _remoteTracks.value = emptyList()
        _connections.value = emptyMap()
    }

    /**
     * Opened under the lock, so two reconciles cannot open one device twice;
     * closed after it, because a close waits for the signalling thread, and
     * the signalling thread must never find this lock held while it waits.
     */
    private fun reconcile(devices: Set<String>) {
        val closing = synchronized(lock) {
            if (!callActive) return
            links.reconcile(lock, devices) { openLink(it) }
        }
        for ((device, link) in closing) {
            // The one thing a dropped call's log has to say: the device left
            // the roster, and in what state its connection was.
            Log.i("KithMootMedia", "peer=${device.take(8)} closed: left the roster state=${_connections.value[device]}")
            runCatching { link.close() }
            _connections.update { it - device }
            _remoteTracks.update { current -> current.filterNot { it.device == device } }
        }
        applyRung()
    }

    private fun managedLinkFor(device: String): ManagedLink? = links[device]

    /**
     * Throw a pair's connection away and open another.
     *
     * The only repair for a pair whose two ends disagree about which connection
     * they are on (H2), and the ladder's step once an ICE restart has not
     * brought media back. `open` is false when the rebuild was caused by an
     * offer the far end is still retransmitting: the new link answers that,
     * rather than opening a generation of its own and glaring with it.
     */
    private fun rebuildLink(device: String, gen: Long, open: Boolean) {
        val old = synchronized(lock) {
            val current = links[device]
            if (!callActive || current == null) return
            links.remove(device)
            links.put(device, openLink(device, gen, open))
            current
        }
        // Outside the lock: see `reconcile`. The old link's callbacks are
        // already ignored, because it is no longer the device's current link.
        runCatching { old.close() }
        _remoteTracks.update { current -> current.filterNot { it.device == device } }
    }

    /**
     * The far end is not speaking profile 2 after all. Start the pair again as
     * profile 1.
     *
     * Section 2.3 calls this a downgrade, and it is what a far end reloading
     * into an older build looks like. The connection goes because everything on
     * it was addressed to a session the far end no longer has.
     */
    private fun reopenAsProfileOne(device: String) {
        val old = synchronized(lock) {
            val current = links[device]
            if (!callActive || current == null) return
            links.remove(device)
            links.put(device, openLink(device, profileTwo = false))
            current
        }
        runCatching { old.close() }
        _remoteTracks.update { current -> current.filterNot { it.device == device } }
    }

    private fun openLink(
        device: String,
        gen: Long = 0,
        openGeneration: Boolean = true,
        profileTwo: Boolean = CALL_PROFILE_2_ENABLED && device in session.profileTwoDevices.value,
    ): ManagedLink {
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
        // A pair is profile 2 only when both ends say so: this build behind its
        // own switch, the far end in its roster entry (the default above).
        // Either side silent and the pair keeps the add-and-remove path it has
        // always had, which is what every client before this one speaks.
        val managed = ManagedLink(device, profileTwo, gen.coerceAtLeast(1), openGeneration)
        val connection = factory.createPeerConnection(configuration, managed.observer)
            ?: throw IllegalStateException("could not create a peer connection to \$device")
        managed.attach(connection)
        // Judged per link rather than per publish, so a device that arrives
        // after the rule was set is judged by the same rule.
        managed.syncLocalTracks(tracksFor(device))
        return managed
    }

    /**
     * An arriving body, in the shape the negotiation machine reads.
     *
     * The mirror of [sendEnvelope], field for field. [SignalEnvelope.toDevice]
     * carries the device it came FROM here, which is the device this link is
     * to; nothing downstream reads it, and naming the pair either way names the
     * same pair.
     */
    private fun inboundEnvelope(from: String, body: SignalBody) = SignalEnvelope(
        toDevice = from,
        type = body.type,
        roomId = body.roomId,
        sdp = body.sdp,
        candidate = body.candidate,
        gen = body.gen,
        conn = body.conn,
        peerConn = body.peerConn,
        first = body.first,
        seq = body.seq,
        candidates = body.candidates,
        ack = body.ack,
        re = body.re,
        restart = body.restart,
        slots = body.slots,
        rx = body.rx,
    )

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
            for ((device, link) in links.snapshot()) link.syncLocalTracks(tracksFor(device, tracks))
        }
        applyRung()
    }

    /** What the camera sends right now; see [VideoLadder]. */
    @Volatile private var rung: VideoRung = VideoLadder.FULL

    /**
     * Fit the camera to the number of devices it goes to.
     *
     * Called after the links or the audience move, and outside the engine
     * lock: adapting the source and setting sender parameters both wait on
     * libwebrtc's threads, and nothing that waits on them may hold the lock
     * (see [reconcile]). A sender added later is capped as it is added.
     */
    private fun applyRung() {
        val peers = links.devices.count { runCatching { audience(it) }.getOrDefault(false) }
        val next = VideoLadder.rungFor(peers)
        if (next == rung) return
        rung = next
        Log.i("KithMootMedia", "camera rung peers=$peers ${next.width}x${next.height}@${next.fps} maxBitrateBps=${next.maxBitrateBps}")
        localMedia.adaptCamera(next)
        for (link in links.values()) link.capCamera(next.maxBitrateBps)
    }

    private fun tracksFor(device: String, tracks: List<LocalTrack> = localMedia.tracks.value): List<LocalTrack> =
        if (runCatching { audience(device) }.getOrDefault(false)) tracks else emptyList()

    /**
     * The one place an outbound signal becomes a wire body.
     *
     * Field for field, in the order `SignalBody.toJson` writes them, so that
     * whatever puts an envelope together - the negotiation machine, or the
     * reliable channel retransmitting one - reaches the relay as the same bytes
     * the web client would have sent.
     */
    private suspend fun sendEnvelope(envelope: SignalEnvelope) {
        session.sendSignal(
            toDevice = envelope.toDevice,
            body = SignalBody(
                type = envelope.type,
                roomId = envelope.roomId,
                sdp = envelope.sdp,
                candidate = envelope.candidate,
                gen = envelope.gen,
                conn = envelope.conn,
                peerConn = envelope.peerConn,
                seq = envelope.seq,
                first = envelope.first,
                candidates = envelope.candidates,
                ack = envelope.ack,
                re = envelope.re,
                restart = envelope.restart,
                slots = envelope.slots,
                rx = envelope.rx,
            ),
        )
    }

    private suspend fun onLocalTracksChanged(tracks: List<LocalTrack>) {
        // Tell the room what we are publishing, so a receiver can map an
        // incoming WebRTC track back to the role we said it was for.
        // `muted` rides along: the room needs to tell somebody who is quiet
        // from somebody who has gone, and an absent track cannot say which.
        // `muted` rides along, and only when it is true: the room needs to tell
        // somebody who is quiet from somebody who has gone, and an absent track
        // cannot say which. An unmuted advert carries nothing, so the wire is
        // byte-identical for everyone who never mutes.
        session.setTracks(tracks.map { TrackRef(it.trackId, it.role, if (it.muted) true else null) })
        synchronized(lock) {
            for ((device, link) in links.snapshot()) link.syncLocalTracks(tracksFor(device, tracks))
        }
    }

    /**
     * One peer connection, its negotiation machine, and the senders we have
     * added to it.
     */
    private inner class ManagedLink(
        val device: String,
        /** Whether this pair speaks fixed media slots. Decided once, at open,
         *  from both ends' roster entries; a pair never changes profile
         *  without the connection being rebuilt. */
        val profileTwo: Boolean,
        /** The generation this connection opens at, if it opens one. */
        private val generation: Long = 1,
        /**
         * Whether this side opens the generation at all.
         *
         * False when the rebuild was caused by an offer the far end is still
         * retransmitting: this connection answers that one rather than opening
         * a generation of its own and glaring with it.
         */
        private val openGeneration: Boolean = true,
    ) {

        /** This pair's ladder. Fed one sample every two seconds; owns no timer
         *  and no connection of its own. */
        private val health = PairHealth()

        @Volatile private var closed = false
        private var connection: PeerConnection? = null

        /**
         * Held while a speaking poll starts a statistics read and while
         * [close] marks the link closed, so the poll never calls into a
         * connection that is being disposed. Never taken on a WebRTC
         * callback, and never held across the close itself.
         */
        private val statsLock = Any()

        /** Last `totalAudioEnergy` and `totalSamplesDuration` per stats id. */
        private val audioEnergy = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Double>>()
        // Read outside the engine lock by `capCamera`; written under it.
        private val senders = java.util.concurrent.ConcurrentHashMap<String, RtpSender>()
        private val received = java.util.concurrent.ConcurrentHashMap<String, MediaStreamTrack>()
        private var handle: WebRtcPeerConnection? = null
        lateinit var link: PeerLink
            private set

        val observer = object : PeerConnection.Observer {
            // currentDirection settling is driven by WebRtcPeerConnection's
            // `onDescriptionApplied` hook below, which fires right after
            // setRemoteDescription and, on the answering side, after our own
            // setLocalDescription applies the answer - earlier and more
            // precisely than waiting for `stable`, which the answerer's own
            // offer application never reaches until it has answered. See
            // refreshRemoteTracks.
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state != null) updateConnectionState(state.name.lowercase())
            }
            /**
             * The transport's own verdict, which used to be recorded and
             * otherwise ignored.
             *
             * It is one of the ladder's two inputs: a connection that says it
             * is `disconnected` for six seconds has told us something the
             * statistics would take longer to, and `connected` restarts every
             * grace, because nothing was ever going to arrive before it.
             */
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (closed || state == null) return
                val name = state.name.lowercase()
                updateConnectionState(name)
                if (profileTwo) health.onConnectionState(name, System.currentTimeMillis())
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                val value = candidate ?: return
                engineScope.launch { link.onLocalCandidate(IceCandidateData(value.sdp, value.sdpMid, value.sdpMLineIndex)) }
            }

            override fun onRenegotiationNeeded() {
                scope.launch {
                    try { link.onNegotiationNeeded() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { updateConnectionState("failed") }
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

            // Unified Plan delivers the receiver here. Do not rely on the
            // older onAddTrack callback also firing: refreshRemoteTracks reads
            // the separately owned objects in `received`, because a fresh
            // getTransceivers() snapshot disposes its previous Java wrappers.
            override fun onTrack(transceiver: RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { received[it.id()] = it }
                refreshRemoteTracks()
            }
        }

        fun attach(connection: PeerConnection) {
            this.connection = connection
            val handle = WebRtcPeerConnection(
                connection,
                ::refreshRemoteTracks,
                // Profile 1 adds and removes senders on the connection
                // directly, so this map is the only thing that can say
                // whether a repeated offer would be answered the same way.
                localMedia = { runCatching { senders.keys.toSet() }.getOrNull() },
                cameraBitrate = { rung.maxBitrateBps },
            )
            this.handle = handle
            link = PeerLink(
                localDevice = session.identity.devicePubkey,
                remoteDevice = device,
                connection = handle,
                roomId = session.room.roomId,
                send = ::sendEnvelope,
                callProfile = if (profileTwo) CALL_PROFILE_2 else 1,
                scope = engineScope,
                onRebuild = { generation, open -> rebuildLink(device, generation, open) },
                onDowngrade = {
                    // The far end reloaded into a build that does not speak
                    // this profile. Everything from here would be addressed to
                    // a connection it no longer has, so the pair starts again
                    // as profile 1 - which its roster entry will now say, and
                    // which is the path every client before this work speaks.
                    reopenAsProfileOne(device)
                },
                onHealthSignal = { rx -> carry(health.onHealth(rx, System.currentTimeMillis())) },
            )
            // Amendment A1: exactly one side creates the four transceivers, and
            // the impolite side is the one, because politeness is a total order
            // both ends compute from the pubkeys alone. The polite side builds
            // an empty connection and answers what arrives, so it never has
            // four m-lines of its own to give up in a glare. There is no
            // negotiation about who negotiates.
            if (profileTwo && openGeneration && !link.polite) {
                scope.launch {
                    try {
                        link.openSlots(
                            tracks = tracksFor(device).map { it.slot() },
                            // The ladder already chose the number; only a
                            // first open has none to carry.
                            gen = generation,
                        )
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { updateConnectionState("failed") }
                }
            }
        }

        /**
         * Publish state only while this is still the device's current link.
         * Native WebRTC callbacks can arrive after close/rebuild; without the
         * identity check an old callback can overwrite the new link's state.
         *
         * Never under the engine lock. This runs on libwebrtc's signalling
         * thread, and closing a connection waits for that thread: taking the
         * lock here while the engine held it through a close was the deadlock
         * behind every freeze on 0.6.9.
         */
        private fun updateConnectionState(state: String) {
            if (!closed && callActive && links.isCurrent(device, this)) {
                _connections.update { it + (device to state) }
            }
        }

        /**
         * One sample of this pair's health, and whatever it decided.
         *
         * A slot nobody is sending on is not a slot that has failed, so the
         * roster's adverts say which of the four are expected to carry
         * anything.
         */
        suspend fun sampleHealth() {
            if (closed || !::link.isInitialized) return
            val slots = link.slotMap ?: return
            val live = session.participants.value
                .asSequence()
                .flatMap { it.tracks.asSequence() }
                .filter { it.device == device }
                .map { it.role }
                .toSet()
            val progress = runCatching { link.stats() }.getOrDefault(emptyList())
            carry(health.sample(System.currentTimeMillis(), slots, live, progress))
        }

        /** Carry out what the ladder decided. */
        private suspend fun carry(actions: List<HealthAction>) {
            for (action in actions) {
                if (closed) return
                when (action) {
                    // One dead slot on a transport that is plainly fine. The
                    // far end puts the track back; every other slot on this
                    // connection carries on undisturbed.
                    is HealthAction.ReportDead -> link.reportHealth(action.roles.associateWith { "dead" })
                    is HealthAction.RefreshSlot -> link.refreshSlot(action.role)
                    HealthAction.RestartIce -> link.restartIce()
                    HealthAction.Rebuild ->
                        rebuildLink(device, nextGeneration(link.generation, link.lastRemoteGeneration), open = true)
                }
            }
        }

        /**
         * The loudest microphone this device is sending us, on libwebrtc's
         * peak-based scale; null when there is no connection or no audio.
         * A screen share's own sound is left out: a video playing on
         * somebody's laptop is not them speaking.
         */
        suspend fun speechLevel(): Double? = suspendCancellableCoroutine { continuation ->
            val started = synchronized(statsLock) {
                val pc = connection
                !closed && pc != null && runCatching {
                    pc.getStats { report ->
                        val level = runCatching { speechLevelFrom(report) }.getOrNull()
                        if (continuation.isActive) continuation.resume(level)
                    }
                }.isSuccess
            }
            if (!started && continuation.isActive) continuation.resume(null)
        }

        private fun speechLevelFrom(report: org.webrtc.RTCStatsReport): Double? {
            if (closed) return null
            val remote = _remoteTracks.value.filter { it.device == device }
            val adverts = session.participants.value.asSequence()
                .flatMap { it.tracks.asSequence() }
                .filter { it.device == device }
                .associate { it.trackId to it.role }
            var loudest: Double? = null
            for (stats in report.statsMap.values) {
                if (stats.type != "inbound-rtp") continue
                val members = stats.members
                if ((members["kind"] ?: members["mediaType"]) != "audio") continue
                val mid = members["mid"] as? String
                val bound = remote.firstOrNull { mid != null && it.mid == mid }
                val advertised = bound?.trackId ?: members["trackIdentifier"] as? String
                val role = bound?.role ?: advertised?.let { adverts[it] }
                    ?: advertised?.takeIf { it.startsWith("${Roles.SCREEN_AUDIO}-") }?.let { Roles.SCREEN_AUDIO }
                if (role == Roles.SCREEN_AUDIO) continue
                val energy = (members["totalAudioEnergy"] as? Number)?.toDouble()
                val duration = (members["totalSamplesDuration"] as? Number)?.toDouble()
                val previous = audioEnergy[stats.id]
                if (energy != null && duration != null) audioEnergy[stats.id] = energy to duration
                val level = (if (previous != null && energy != null && duration != null) {
                    levelFromEnergy(previous.first, previous.second, energy, duration)
                } else null) ?: (members["audioLevel"] as? Number)?.toDouble() ?: continue
                loudest = maxOf(loudest ?: 0.0, level)
            }
            return loudest
        }

        fun reportMediaProgress() {
            if (closed) return
            connection?.getStats { report ->
                if (closed) return@getStats
                val relevant = report.statsMap.values
                    .filter { it.type == "inbound-rtp" || it.type == "outbound-rtp" }
                    .map { StatsEntry(it.type, it.members) }
                val summary = summariseMediaProgress(relevant)
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
            val slotMap = if (profileTwo && ::link.isInitialized) link.slotMap else null
            val bindings = remoteTracksFor(device, pc, received.values.toList(), slotMap)
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
                ?.let { sender ->
                    senders[track.trackId] = sender
                    if (track.role == Roles.CAMERA) capSender(sender, rung.maxBitrateBps)
                }
        }

        /**
         * Bound what this pair's camera sender may spend; see [VideoLadder].
         *
         * A profile-1 pair's senders are the ones `addLocalTrack` kept; a
         * profile-2 pair's live in its slots, which the handle reads.
         */
        fun capCamera(maxBitrateBps: Int) {
            if (closed) return
            if (profileTwo) {
                handle?.capCameraSenders(maxBitrateBps)
                return
            }
            for ((id, sender) in senders) if (isCameraTrackId(id)) capSender(sender, maxBitrateBps)
        }

        /**
         * Publish this set of tracks to this peer.
         *
         * On a profile-2 pair every change is a `setTrack` into a slot that
         * already exists: a camera toggle, a share, a microphone pipeline swap
         * and an audience narrowing are all the same act, and none of them
         * renegotiates, so none of them has an answer that can be lost (D1).
         *
         * On a profile-1 pair it is the add-and-remove path exactly as it has
         * always been, because that is what every far end from before this work
         * speaks - including every Android build so far.
         */
        fun syncLocalTracks(tracks: List<LocalTrack>) {
            if (profileTwo) {
                if (!::link.isInitialized) return
                engineScope.launch { runCatching { link.applyTracks(tracks.map { it.slot() }) } }
                return
            }
            val wanted = tracks.associateBy { it.trackId }
            for (track in tracks) addLocalTrack(track)
            for (id in senders.keys.toList() - wanted.keys) {
                senders.remove(id)?.let { sender -> runCatching { connection?.removeTrack(sender) } }
            }
        }

        suspend fun onRemoteSignal(body: SignalEnvelope) {
            if (!closed) link.onRemoteSignal(body)
        }

        fun close() {
            synchronized(statsLock) { closed = true }
            senders.clear()
            received.clear()
            if (::link.isInitialized) link.close() else connection?.let { runCatching { it.dispose() } }
            connection = null
        }
    }

}

/**
 * The one stream id everything this device sends belongs to, whether it went
 * on with `addTrack` or into a fixed slot.
 *
 * A receiver can tell one device's media from another's by it, even before the
 * roster catches up.
 */
internal const val STREAM_ID: String = "kithmoot"

/**
 * How often a profile-2 pair's statistics are read.
 *
 * Two seconds is the spec's, and it is the sampling interval every threshold in
 * section 3.4 is expressed against: a six second silence is three samples, not
 * a stopwatch.
 */
internal const val HEALTH_SAMPLE_MS: Long = 2_000

/**
 * How often who-is-speaking is read: five times a second. Fast enough that a
 * cue arrives with the first word, slow enough that a statistics report per
 * connection is not a cost anybody notices.
 */
internal const val SPEAKING_SAMPLE_MS: Long = 200

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
