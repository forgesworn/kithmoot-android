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
 * A group's own relay will arrive with its own credential and reach the
 * engine through [WebRtcEngine.updateIceServers]; until then there is none.
 */
internal object CallIceServers {
    private const val HOST = "kithmoot.forgesworn.dev"

    /** STUN only. Never a TURN URL: there is no credential to go with one. */
    internal val STUN_URLS: List<String> = listOf("stun:$HOST:3478")

    fun resolve(): List<PeerConnection.IceServer> =
        STUN_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() }

    /**
     * The relay alone, for "Hide my IP address on calls": no relay is known, so
     * null, and such a call must not start, never fall back to a direct one
     * (as the web's `relayOnlyIceConfiguration`).
     */
    fun resolveRelayOnly(): List<PeerConnection.IceServer>? = null

    /** True when [servers] include a relay, the only thing that gets a call through every network. */
    fun hasRelay(servers: List<PeerConnection.IceServer>): Boolean =
        servers.any { server -> server.urls.any { it.startsWith("turn:", true) || it.startsWith("turns:", true) } }
}
