package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.BuildConfig
import org.webrtc.PeerConnection

/**
 * The ICE servers a call is built with.
 *
 * From 1 November 2026 a call is back on ForgeSworn's own relay by default
 * (see [DefaultTurn]): when the person has no relay of their own, no Spry node
 * and no public relay chosen, it asks for a short-lived TURN login and adds
 * it. If that fails the call goes on with STUN alone, and when it then cannot
 * connect, the call screen says a relay is missing (as the web's
 * `RELAY_MISSING_NOTICE`).
 *
 * STUN, in order: the person's own STUN servers (Settings › Calls › Advanced), else
 * the STUN listener of their own call relay (none for a `turns:`-only relay),
 * else the build's default, [DEFAULT_STUN_URLS]: Google's, then Cloudflare's
 * public servers. A STUN server sees the device's IP address when a call
 * starts and carries no media.
 *
 * An explicitly chosen device relay adds itself. Credentials are attached
 * only to the addresses saved alongside them.
 */
internal object CallIceServers {
    /** From `-Pkithmoot.defaultStun` at build time; empty means none. */
    internal val DEFAULT_STUN_URLS: List<String> = defaultStunUrls(BuildConfig.DEFAULT_STUN_URLS)

    internal const val GOOGLE_STUN = "stun:stun.l.google.com:19302"
    internal const val CLOUDFLARE_STUN = "stun:stun.cloudflare.com:3478"

    /** The stock build default: Google first, then Cloudflare (as the web's `DEFAULT_STUN_URLS`). */
    internal val STOCK_STUN: List<String> = listOf(GOOGLE_STUN, CLOUDFLARE_STUN)

    internal fun defaultStunUrls(configured: String): List<String> =
        configured.split(',').map(String::trim).filter(String::isNotEmpty).filter(CallStunConfig::isStunUrl)

    /** The STUN addresses a call asks, before any relay is added. Never a TURN URL. */
    fun stunUrls(relay: CallRelayConfig? = null, ownStun: CallStunConfig? = null, defaults: List<String> = DEFAULT_STUN_URLS): List<String> =
        ownStun?.urls
            ?: relay?.urls?.filter { it.startsWith("turn:", ignoreCase = true) }
                ?.map { "stun:" + it.substringAfter(':').substringBefore('?') }?.distinct()
            ?: defaults

    fun resolve(relay: CallRelayConfig? = null, ownStun: CallStunConfig? = null, defaults: List<String> = DEFAULT_STUN_URLS): List<PeerConnection.IceServer> {
        val stun = stunUrls(relay, ownStun, defaults).map { PeerConnection.IceServer.builder(it).createIceServer() }
        return stun + (resolveRelayOnly(relay) ?: emptyList())
    }

    /**
     * The relay alone, for "Hide my IP address on calls": without a saved
     * relay this is null, and such a call must not start or fall back to direct
     * (as the web's `relayOnlyIceConfiguration`). No STUN server is asked.
     */
    fun resolveRelayOnly(relay: CallRelayConfig? = null): List<PeerConnection.IceServer>? = relay?.let {
        listOf(PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.credential).createIceServer())
    }

    /**
     * ForgeSworn's default relay: STUN as for a call with no relay (the
     * person's own servers, else the build's default) and then the TURN login.
     * Relay-only gets the login alone, no STUN, as for any other relay.
     */
    fun resolveDefaultTurn(login: CallRelayConfig, ownStun: CallStunConfig?, relayOnly: Boolean, defaults: List<String> = DEFAULT_STUN_URLS): List<PeerConnection.IceServer> {
        val turn = PeerConnection.IceServer.builder(login.urls).setUsername(login.username).setPassword(login.credential).createIceServer()
        if (relayOnly) return listOf(turn)
        return (ownStun?.urls ?: defaults).map { PeerConnection.IceServer.builder(it).createIceServer() } + turn
    }

    /**
     * A discovered public relay, pinned to its checked address. Every URL in
     * the relay's server is an IP literal with the name set as its hostname,
     * which libwebrtc requires when a hostname is given. STUN, when asked,
     * comes from the person's own servers, else the same pinned addresses
     * (none for a `turns:`-only relay, as for a hand-entered one), so nothing
     * here is looked up by libwebrtc.
     */
    fun resolveDiscovered(relay: PinnedPublicRelay, ownStun: CallStunConfig?, relayOnly: Boolean): List<PeerConnection.IceServer> {
        val turn = PeerConnection.IceServer.builder(relay.urls).setUsername(relay.username).setPassword(relay.credential)
            .setHostname(relay.host).createIceServer()
        if (relayOnly) return listOf(turn)
        val stun = ownStun?.urls
            ?: relay.urls.filter { it.startsWith("turn:", ignoreCase = true) }
                .map { "stun:" + it.substringAfter(':').substringBefore('?') }.distinct()
        return stun.map { PeerConnection.IceServer.builder(it).createIceServer() } + turn
    }

    /** True when [servers] include a relay, the only thing that gets a call through every network. */
    fun hasRelay(servers: List<PeerConnection.IceServer>): Boolean =
        servers.any { server -> server.urls.any { it.startsWith("turn:", true) || it.startsWith("turns:", true) } }

    /** Who answers STUN for a person who has set none: words for Settings. */
    fun defaultStunWords(defaults: List<String> = DEFAULT_STUN_URLS): String = when {
        defaults.isEmpty() -> "No STUN server unless you set one"
        defaults == STOCK_STUN -> "Google’s and Cloudflare’s unless you set your own"
        defaults == listOf(CLOUDFLARE_STUN) -> "Cloudflare’s unless you set your own"
        else -> defaults.joinToString(", ") { it.substringAfter(':').replace(Regex(":[0-9]+$"), "") } + " unless you set your own"
    }
}
