package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.protocol.RELAY_TEST_RUN_MS
import dev.forgesworn.kithmoot.protocol.RelayTestObservations
import dev.forgesworn.kithmoot.protocol.RelayTestVerdict
import dev.forgesworn.kithmoot.protocol.relayTestVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.math.BigInteger
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The outside test of a member's built-in call relay, on this phone as the
 * tester (docs/protocol.md, "Relay outside test"; the web's
 * `runRelayOutsideTest` in `app/src/relay-outside-test.ts`).
 *
 * Peer A is relay-only on the host's relay. Peer B has no TURN and uses the
 * relay's listener as its STUN server, so it learns its own public address
 * and A's relay can let it in. B's checks then reach A's relayed address from
 * B's public address, through the host's allocation port, and a data-channel
 * echo plus the selected pairs prove listener and allocation inbound
 * together. Both peers are on this phone and exchange candidates in memory;
 * the only traffic leaving it goes to the host's relay, which sees this
 * phone's address as it would on any call through it. No camera or
 * microphone is opened, nothing is logged or kept, and only the verdict
 * leaves this function.
 */
internal object RelayOutsideTestRun {
    private const val SENT = "kithmoot-relay-outside-test"

    /** The single IPv4 literal of the relay's `turn:` URLs, as the web's `turnHostOf`. */
    internal fun turnHostOf(urls: List<String>): String? =
        urls.mapNotNull { Regex("^turns?:([0-9.]+):\\d+(?:\\?|$)", RegexOption.IGNORE_CASE).find(it.trim())?.groupValues?.get(1) }
            .toSet().singleOrNull()

    /** `stun:<host>:<port>` from the relay's UDP `turn:` URL, as the web's
     *  `stunFromTurnUrl`; null when it has none. */
    internal fun stunFromRelay(urls: List<String>): String? =
        urls.map(String::trim).firstOrNull { Regex("^turn:[^?]+\\?transport=udp$", RegexOption.IGNORE_CASE).matches(it) }
            ?.let { "stun:" + it.substringAfter(':').substringBefore('?').trim() }

    /** The type and address of an SDP `candidate:` line: fields after `typ`, and the fifth. */
    internal fun candidateTypeAndAddress(sdp: String): Pair<String?, String?> {
        val parts = sdp.trim().split(' ')
        val at = parts.indexOf("typ")
        return (if (at >= 0) parts.getOrNull(at + 1) else null) to parts.getOrNull(4)
    }

    /** What one peer's selected path looked like: the local candidate of its
     *  nominated, succeeded pair that received bytes. */
    internal data class Path(val localType: String?, val localAddress: String?)

    internal fun selectedPath(report: RTCStatsReport): Path? {
        val stats = report.statsMap
        for (stat in stats.values) {
            if (stat.type != "candidate-pair") continue
            val members = stat.members
            if (members["state"] != "succeeded" || members["nominated"] != true || !receivedBytes(members["bytesReceived"])) continue
            val local = stats[members["localCandidateId"] as? String]?.members
            return Path(local?.get("candidateType") as? String, (local?.get("address") ?: local?.get("ip")) as? String)
        }
        return null
    }

    /** libwebrtc reports an unsigned counter as a [BigInteger], others as whichever boxed number it chose. */
    private fun receivedBytes(value: Any?): Boolean = when (value) {
        is BigInteger -> value.signum() > 0
        is Number -> value.toLong() > 0
        else -> false
    }

    suspend fun run(context: Context, relay: CallRelayConfig, timeoutMs: Long = RELAY_TEST_RUN_MS): RelayTestVerdict = withContext(Dispatchers.Default) {
        val turnHost = turnHostOf(relay.urls) ?: ""
        var relayed = false
        val reflexive = mutableListOf<String>()
        val hosts = mutableListOf<String>()
        var local: String? = null
        var echoed = false
        var pathA = false
        var pathB = false
        val lock = Any()

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
        val peers = mutableListOf<PeerConnection>()
        val channels = mutableListOf<DataChannel>()
        var closed = false
        // Index 0 is peer A, 1 is peer B; read by the candidate callbacks.
        val pcs = arrayOfNulls<PeerConnection>(2)
        val ready = BooleanArray(2)
        val pending = arrayOf(mutableListOf<IceCandidate>(), mutableListOf<IceCandidate>())
        /** Hands [candidate] to peer [to], or keeps it until that peer has its remote description. */
        fun forward(to: Int, candidate: IceCandidate) {
            val target = synchronized(lock) {
                if (closed) return
                if (!ready[to]) { pending[to] += candidate; return }
                pcs[to]
            }
            runCatching { target?.addIceCandidate(candidate) }
        }
        /** Marks peer [to] ready and hands it every candidate that waited for it. */
        fun drain(to: Int) {
            val (target, waiting) = synchronized(lock) { ready[to] = true; pcs[to] to pending[to].toList().also { pending[to].clear() } }
            for (candidate in waiting) runCatching { target?.addIceCandidate(candidate) }
        }
        try {
            withTimeoutOrNull(timeoutMs) {
                val echo = CompletableDeferred<Unit>()
                val stun = stunFromRelay(relay.urls)
                val configA = PeerConnection.RTCConfiguration(requireNotNull(CallIceServers.resolveRelayOnly(relay))).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    iceTransportsType = PeerConnection.IceTransportsType.RELAY
                }
                val configB = PeerConnection.RTCConfiguration(
                    listOfNotNull(stun?.let { PeerConnection.IceServer.builder(it).createIceServer() }),
                ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
                val a = requireNotNull(factory.createPeerConnection(configA, object : Observer() {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate ?: return
                        if (candidateTypeAndAddress(candidate.sdp).first == "relay") synchronized(lock) { relayed = true }
                        forward(1, candidate)
                    }
                }))
                synchronized(lock) { peers += a; pcs[0] = a }
                val b = requireNotNull(factory.createPeerConnection(configB, object : Observer() {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate ?: return
                        val (type, address) = candidateTypeAndAddress(candidate.sdp)
                        synchronized(lock) {
                            val list = when (type) { "srflx" -> reflexive; "host" -> hosts; else -> null }
                            if (list != null && address != null && list.size < 32 && address !in list) list += address
                        }
                        forward(0, candidate)
                    }
                    override fun onDataChannel(channel: DataChannel?) {
                        channel ?: return
                        synchronized(lock) { if (closed) return; channels += channel }
                        channel.registerObserver(object : ChannelObserver() {
                            override fun onMessage(buffer: DataChannel.Buffer?) {
                                if (buffer != null && text(buffer) == SENT) runCatching { channel.send(utf8(SENT)) }
                            }
                        })
                    }
                }))
                synchronized(lock) { peers += b; pcs[1] = b }
                val channel = requireNotNull(a.createDataChannel("relay-outside-test", DataChannel.Init()))
                synchronized(lock) { channels += channel }
                channel.registerObserver(object : ChannelObserver() {
                    override fun onStateChange() {
                        if (channel.state() == DataChannel.State.OPEN) runCatching { channel.send(utf8(SENT)) }
                    }
                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        if (buffer != null && text(buffer) == SENT) echo.complete(Unit)
                    }
                })
                awaitSet { a.setLocalDescription(it) }
                awaitSet { b.setRemoteDescription(it, requireNotNull(a.localDescription)) }
                drain(1)
                awaitSet { b.setLocalDescription(it) }
                awaitSet { a.setRemoteDescription(it, requireNotNull(b.localDescription)) }
                drain(0)
                echo.await()
                synchronized(lock) { echoed = true }
                val selectedA = selectedPath(stats(a))
                val selectedB = selectedPath(stats(b))
                synchronized(lock) {
                    pathA = selectedA?.localType == "relay"
                    pathB = selectedB != null && selectedB.localType != "relay"
                    if (selectedB?.localAddress != null) local = selectedB.localAddress
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The verdict says which part did not work.
        } finally {
            val (toClose, channelsToClose) = synchronized(lock) { closed = true; peers.toList() to channels.toList() }
            for (open in channelsToClose) { runCatching { open.unregisterObserver() }; runCatching { open.close() }; runCatching { open.dispose() } }
            for (pc in toClose) { runCatching { pc.close() }; runCatching { pc.dispose() } }
            runCatching { factory.dispose() }
            runCatching { audio.release() }
        }
        // The verdict says which part did not work.
        synchronized(lock) {
            relayTestVerdict(RelayTestObservations(turnHost = turnHost, relayed = relayed, reflexive = reflexive.toList(), hosts = hosts.toList(),
                local = local, echoed = echoed, pathA = pathA, pathB = pathB))
        }
    }

    private fun utf8(value: String) = DataChannel.Buffer(ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8)), false)

    private fun text(buffer: DataChannel.Buffer): String? {
        if (buffer.binary || buffer.data.remaining() > 256) return null
        val bytes = ByteArray(buffer.data.remaining())
        buffer.data.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private suspend fun stats(pc: PeerConnection): RTCStatsReport = suspendCancellableCoroutine { continuation ->
        pc.getStats { report -> if (continuation.isActive) continuation.resume(report) }
    }

    /** The implicit (create-and-set) description calls, as `WebRtcPeerConnection` uses them. */
    private suspend fun awaitSet(action: (SdpObserver) -> Unit) = suspendCancellableCoroutine { continuation ->
        action(object : SdpObserver {
            override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
            override fun onSetFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("set failed")) }
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("create failed")) }
        })
    }

    private open class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    private open class ChannelObserver : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = Unit
        override fun onMessage(buffer: DataChannel.Buffer?) = Unit
    }
}
