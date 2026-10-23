package dev.forgesworn.kithmoot.media

import org.webrtc.PeerConnection

/**
 * The ICE servers a call is built with.
 *
 * ForgeSworn no longer runs a call relay for the public (22 October 2026), so
 * there is no TURN credential to ask for: the app makes no request to `/turn`.
 * A call gets STUN on ForgeSworn's host, which only tells a device its own
 * address and carries nobody's media, and connects directly or not at all.
 * When a call cannot connect that way, the call screen says a relay is
 * missing (as the web's `RELAY_MISSING_NOTICE`).
 *
 * An explicitly chosen device relay replaces these defaults. Credentials
 * are attached only to the addresses saved alongside them.
 */
internal object CallIceServers {
    private const val HOST = "kithmoot.forgesworn.dev"

    /** STUN only. Never a TURN URL: there is no credential to go with one. */
    internal val STUN_URLS: List<String> = listOf("stun:$HOST:3478")

    fun resolve(relay: CallRelayConfig? = null): List<PeerConnection.IceServer> {
        if (relay == null) return STUN_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val stun = relay.urls.filter { it.startsWith("turn:", ignoreCase = true) }
            .map { "stun:" + it.substringAfter(':').substringBefore('?') }.distinct()
            .map { PeerConnection.IceServer.builder(it).createIceServer() }
        return stun + requireNotNull(resolveRelayOnly(relay))
    }

    /**
     * The relay alone, for "Hide my IP address on calls": without a saved
     * relay this is null, and such a call must not start or fall back to direct
     * (as the web's `relayOnlyIceConfiguration`).
     */
    fun resolveRelayOnly(relay: CallRelayConfig? = null): List<PeerConnection.IceServer>? = relay?.let {
        listOf(PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.credential).createIceServer())
    }

    /** True when [servers] include a relay, the only thing that gets a call through every network. */
    fun hasRelay(servers: List<PeerConnection.IceServer>): Boolean =
        servers.any { server -> server.urls.any { it.startsWith("turn:", true) || it.startsWith("turns:", true) } }
}
