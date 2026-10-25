package dev.forgesworn.kithmoot.media

import org.webrtc.PeerConnection

/** Where a call's relay comes from, in the order the selector prefers them. */
internal enum class CallRelaySource { HAND_ENTERED, SPRY, DISCOVERED, NONE }

/** What the selector would give the engine: [servers] is null when nothing may be applied (relay-only with no relay). */
internal class CallIceSelection(val source: CallRelaySource, val servers: List<PeerConnection.IceServer>?) {
    val hasRelay: Boolean get() = servers?.let(CallIceServers::hasRelay) == true
}

/**
 * One room engine's choice of ICE servers, and the only caller of
 * [WebRtcEngine.updateIceServers]. Each change, including each login
 * renewal, is one call there, which restarts ICE once on every link. Order: the hand-entered relay, then Spry
 * (the pairing's own offer, then the room's), then a discovered public relay,
 * then STUN only. A discovered relay is used only when the first two give
 * nothing, and stands down for the rest of the call once Spry supplies one.
 *
 * Relay-only never gets STUN. When a Spry credential lapses the links keep
 * what they had; when a discovered relay goes they get an empty list. With
 * [PeerConnection.IceTransportsType.RELAY] neither reveals this phone's address.
 */
internal class CallIceSelector(
    private val handEntered: CallRelayConfig?,
    val relayOnly: Boolean,
    private val ownStun: CallStunConfig?,
    private val defaults: List<String> = CallIceServers.DEFAULT_STUN_URLS,
) {
    private var spry: CallRelayConfig? = null
    private var discovered: PinnedPublicRelay? = null
    /** Spry has supplied a relay during this call, so a discovered one may not start or return. */
    private var spryTookOver = false
    private var sink: ((List<PeerConnection.IceServer>) -> Unit)? = null
    /** The source and config object the engine last received, compared by identity. */
    private var appliedKey: Pair<CallRelaySource, Any?>? = null

    /** Told whether the engine now has a relay. */
    @Volatile var onRelayChanged: (Boolean) -> Unit = {}
    /** Told when Spry takes over from a discovered relay, so its login can be dropped. */
    @Volatile var onDiscoveredSuperseded: () -> Unit = {}

    @get:Synchronized val source: CallRelaySource get() = choose().source

    /** Whether a discovered relay may be looked up or logged in to now. */
    @Synchronized fun discoveredMayStart(): Boolean = handEntered == null && spry == null && !spryTookOver

    /**
     * The servers to build the engine with. Null refuses the build: relay-only
     * and no relay. With [discoveredChosen] (relay-only, nothing else, a
     * discovered relay to log in to at join), an empty list instead: no
     * candidates at all until the call starts.
     */
    @Synchronized fun initial(discoveredChosen: Boolean): List<PeerConnection.IceServer>? {
        val choice = choose()
        // What the engine is built with counts as applied, so a later change is pushed on attach.
        appliedKey = key(choice)
        if (choice.servers == null && relayOnly && discoveredChosen && discoveredMayStart()) return emptyList()
        return choice.servers
    }

    /** The engine now exists and was built with [initial]; anything that changed since is pushed. */
    fun attach(update: (List<PeerConnection.IceServer>) -> Unit) {
        synchronized(this) {
            sink = update
        }
        publish()
    }

    /** The engine is gone: nothing more is pushed to it. */
    @Synchronized fun detach() { sink = null }

    /** A Spry credential arrived, or (null) the last one lapsed. */
    fun updateSpry(relay: CallRelayConfig?) {
        var superseded = false
        synchronized(this) {
            spry = relay
            if (relay != null && handEntered == null) {
                spryTookOver = true
                if (discovered != null) { discovered = null; superseded = true }
            }
        }
        if (superseded) onDiscoveredSuperseded()
        publish()
    }

    /** A discovered relay's pinned login, or (null) it was lost. Ignored once something higher has taken over. */
    fun updateDiscovered(relay: PinnedPublicRelay?): Boolean {
        synchronized(this) {
            if (relay != null && !discoveredMayStart()) return false
            discovered = relay
        }
        publish()
        return true
    }

    /** The call ended: the discovered relay goes, and Spry may stand aside again for the next call if it has nothing. */
    fun callEnded() {
        synchronized(this) {
            discovered = null
            if (spry == null) spryTookOver = false
        }
        publish()
    }

    @Synchronized fun current(): CallIceSelection = choose()

    private fun publish() {
        val push: List<PeerConnection.IceServer>?
        val relay: Boolean
        val target: ((List<PeerConnection.IceServer>) -> Unit)?
        synchronized(this) {
            val choice = choose()
            relay = choice.hasRelay
            val next = key(choice)
            target = sink
            push = when {
                target == null || next == appliedKey -> null
                choice.servers != null -> choice.servers
                // A discovered relay gone from a relay-only engine: an empty list, so the engine holds no
                // stranger's pinned server or login after the call. No candidates, so nothing leaks either.
                appliedKey?.first == CallRelaySource.DISCOVERED -> emptyList()
                // A Spry credential that lapsed: the engine keeps its last list (the design names an empty
                // push here a bug). That server is the person's own node or the room's, not a stranger's.
                else -> null
            }
            if (push != null || choice.servers == null) appliedKey = next
        }
        if (push != null) target?.invoke(push)
        onRelayChanged(relay)
    }

    private fun key(choice: CallIceSelection): Pair<CallRelaySource, Any?> = choice.source to when (choice.source) {
        CallRelaySource.HAND_ENTERED -> handEntered
        CallRelaySource.SPRY -> spry
        CallRelaySource.DISCOVERED -> discovered
        CallRelaySource.NONE -> null
    }

    private fun choose(): CallIceSelection {
        handEntered?.let { return CallIceSelection(CallRelaySource.HAND_ENTERED, servers(it)) }
        spry?.let { return CallIceSelection(CallRelaySource.SPRY, servers(it)) }
        discovered?.let { relay ->
            if (!spryTookOver) return CallIceSelection(CallRelaySource.DISCOVERED, CallIceServers.resolveDiscovered(relay, ownStun, relayOnly))
        }
        return CallIceSelection(CallRelaySource.NONE, if (relayOnly) null else CallIceServers.resolve(null, ownStun, defaults))
    }

    private fun servers(relay: CallRelayConfig): List<PeerConnection.IceServer> =
        if (relayOnly) requireNotNull(CallIceServers.resolveRelayOnly(relay)) else CallIceServers.resolve(relay, ownStun, defaults)
}
