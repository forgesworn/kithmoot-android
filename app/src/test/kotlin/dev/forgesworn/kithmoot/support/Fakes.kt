package dev.forgesworn.kithmoot.support

import dev.forgesworn.kithmoot.media.IceCandidateData
import dev.forgesworn.kithmoot.media.PeerConnectionHandle
import dev.forgesworn.kithmoot.media.SdpData
import dev.forgesworn.kithmoot.media.SignalType
import dev.forgesworn.kithmoot.media.SignalingState
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelaySocket
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.relay.RelaySocketListener
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription

/**
 * A relay that lives in the test process.
 *
 * It matches the filters the session actually uses - kinds and single-letter
 * tags - and fans an event out to every matching subscriber, which is the only
 * relay behaviour the session depends on.
 */
class FakeRelay {

    /** Everything anybody published, in order. Counting these proves the absence of a storm. */
    val published = mutableListOf<NostrEvent>()

    private val subscriptions = mutableListOf<Subscription>()

    fun transport(): RoomTransport = object : RoomTransport {
        override fun publish(event: NostrEvent) = this@FakeRelay.publish(event)

        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
            val subscription = Subscription(filters)
            return subscription.events
                .onSubscription { subscriptions += subscription }
                .onCompletion { subscriptions -= subscription }
        }
    }

    fun publish(event: NostrEvent) {
        published += event
        for (subscription in subscriptions.toList()) {
            if (subscription.filters.any { matches(it, event) }) subscription.offer(event)
        }
    }

    fun countOfKind(kind: Int): Int = published.count { it.kind == kind }

    /** How many events of a kind one device published. The precise measure of who answered what. */
    fun countFrom(devicePubkey: String, kind: Int): Int =
        published.count { it.pubkey == devicePubkey && it.kind == kind }

    private class Subscription(val filters: List<Filter>) {
        private val _events = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 512)
        val events = _events.asSharedFlow()
        fun offer(event: NostrEvent) {
            _events.tryEmit(event)
        }
    }

    companion object {
        fun matches(filter: Filter, event: NostrEvent): Boolean {
            if (filter.kinds != null && event.kind !in filter.kinds) return false
            if (filter.ids != null && event.id !in filter.ids) return false
            if (filter.authors != null && event.pubkey !in filter.authors) return false
            for ((name, wanted) in filter.tags) {
                val tag = name.removePrefix("#")
                val present = event.tags.filter { it.size >= 2 && it[0] == tag }.map { it[1] }
                if (present.none { it in wanted }) return false
            }
            return true
        }
    }
}

/** A websocket that never touches a network. */
class FakeSocket(val url: String, private val listener: RelaySocketListener) : RelaySocket {
    val sent = mutableListOf<String>()
    var closedByPool = false
        private set

    override fun send(text: String) {
        sent += text
    }

    override fun close() {
        closedByPool = true
    }

    fun open() = listener.onOpen()

    fun drop(reason: String = "test drop") = listener.onClosed(reason)

    fun deliverEvent(subscriptionId: String, event: NostrEvent) =
        listener.onMessage("""["EVENT","$subscriptionId",${event.toCompactJson()}]""")

    fun deliverRaw(text: String) = listener.onMessage(text)

    /** The subscription ids this socket has been asked to open, in order. */
    fun requestedSubscriptions(): List<String> = sent
        .filter { it.startsWith("[\"REQ\"") }
        .map { it.substringAfter("[\"REQ\",\"").substringBefore("\"") }

    fun publishedFrames(): List<String> = sent.filter { it.startsWith("[\"EVENT\"") }
}

class FakeSocketFactory : RelaySocketFactory {
    val opened = mutableListOf<FakeSocket>()

    override fun open(url: String, listener: RelaySocketListener): RelaySocket =
        FakeSocket(url, listener).also { opened += it }

    fun forUrl(url: String): List<FakeSocket> = opened.filter { it.url == url }

    fun openAll() = opened.forEach { it.open() }
}

/**
 * A peer connection that enforces the state rules the real one enforces.
 *
 * In particular `addIceCandidate` throws before a remote description has been
 * applied, exactly as libwebrtc does. Without that the candidate-buffering test
 * would pass whether or not the buffering existed.
 */
class FakePeerConnection : PeerConnectionHandle {

    var state: SignalingState = SignalingState.STABLE
        private set

    val addedCandidates = mutableListOf<IceCandidateData>()
    val remoteDescriptions = mutableListOf<SdpData>()
    val localDescriptions = mutableListOf<SdpData>()
    var rollbacks: Int = 0
        private set
    var closed: Boolean = false
        private set

    private var remoteApplied = false

    /**
     * Holds every [setRemoteDescription] until it is completed.
     *
     * Two negotiation steps that overlap are the whole of I6, and they only
     * overlap around a suspension point. This is that point, made
     * controllable.
     */
    var gate: CompletableDeferred<Unit>? = null

    /** Reject the next [setRemoteDescription] only, then behave normally. */
    var failNextSetRemoteDescription: Boolean = false

    override fun signalingState(): SignalingState = state

    override suspend fun setLocalDescription(): SdpData {
        val description = when (state) {
            SignalingState.STABLE -> {
                state = SignalingState.HAVE_LOCAL_OFFER
                SdpData(SignalType.OFFER, "local-offer")
            }

            SignalingState.HAVE_REMOTE_OFFER -> {
                state = SignalingState.STABLE
                SdpData(SignalType.ANSWER, "local-answer")
            }

            else -> throw IllegalStateException("cannot set a local description in $state")
        }
        localDescriptions += description
        return description
    }

    override suspend fun setRemoteDescription(sdp: SdpData) {
        gate?.await()
        if (failNextSetRemoteDescription) {
            failNextSetRemoteDescription = false
            throw IllegalStateException("setRemoteDescription rejected")
        }
        when (sdp.type) {
            SignalType.OFFER -> {
                if (state != SignalingState.STABLE) {
                    throw IllegalStateException("cannot apply a remote offer in $state")
                }
                state = SignalingState.HAVE_REMOTE_OFFER
            }

            SignalType.ANSWER -> {
                if (state != SignalingState.HAVE_LOCAL_OFFER) {
                    throw IllegalStateException("cannot apply a remote answer in $state")
                }
                state = SignalingState.STABLE
            }

            else -> throw IllegalStateException("unknown description type ${sdp.type}")
        }
        remoteDescriptions += sdp
        remoteApplied = true
    }

    override suspend fun rollbackLocalDescription() {
        rollbacks++
        state = SignalingState.STABLE
    }

    override suspend fun addIceCandidate(candidate: IceCandidateData) {
        if (!remoteApplied) throw IllegalStateException("no remote description yet")
        addedCandidates += candidate
    }

    override fun close() {
        closed = true
        state = SignalingState.CLOSED
    }
}
