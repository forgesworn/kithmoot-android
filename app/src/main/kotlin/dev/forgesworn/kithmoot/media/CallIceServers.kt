package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.BuildConfig
import org.webrtc.PeerConnection

/**
 * The ICE servers a call is built with.
 *
 * ForgeSworn runs no call servers for the public: no relay since 22 October
 * 2026, and no STUN since 23 October 2026. A call asks a STUN server for this
 * device's public address and connects directly or not at all. When a call
 * cannot connect that way, the call screen says a relay is missing (as the
 * web's `RELAY_MISSING_NOTICE`).
 *
 * STUN, in order: the person's own STUN servers (Settings, Connections), else
 * the STUN listener of their own call relay (none for a `turns:`-only relay),
 * else the build's default, [DEFAULT_STUN_URLS]: Cloudflare's public server.
 * A STUN server sees the device's IP address when a call starts and carries
 * no media.
 *
 * An explicitly chosen device relay adds itself. Credentials are attached
 * only to the addresses saved alongside them.
 */
internal object CallIceServers {
    /** From `-Pkithmoot.defaultStun` at build time; empty means none. */
    internal val DEFAULT_STUN_URLS: List<String> = defaultStunUrls(BuildConfig.DEFAULT_STUN_URLS)

    internal const val CLOUDFLARE_STUN = "stun:stun.cloudflare.com:3478"

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

    /** True when [servers] include a relay, the only thing that gets a call through every network. */
    fun hasRelay(servers: List<PeerConnection.IceServer>): Boolean =
        servers.any { server -> server.urls.any { it.startsWith("turn:", true) || it.startsWith("turns:", true) } }

    /** Who answers STUN for a person who has set none: words for Settings. */
    fun defaultStunWords(defaults: List<String> = DEFAULT_STUN_URLS): String = when {
        defaults.isEmpty() -> "No STUN server unless you set one"
        defaults == listOf(CLOUDFLARE_STUN) -> "Cloudflare’s unless you set your own"
        else -> defaults.joinToString(", ") { it.substringAfter(':').replace(Regex(":[0-9]+$"), "") } + " unless you set your own"
    }
}
