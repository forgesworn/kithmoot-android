package dev.forgesworn.kithmoot.callrelay

import java.net.URI

/**
 * The built-in call relay's fixed settings and the rules for the values it is
 * started with (kithmoot docs/design/2026-10-28-built-in-relay.md, 1.6, 3.3
 * and 4.3; spry-turn docs/supervised-mode.md, "Command line"). Ported from the
 * desktop's desktop/relay-supervisor.mjs and relay-identity.mjs.
 */
internal object RelayCaps {
    /** The owner's home defaults (owner decisions, 28 October 2026). The
     *  aggregate cap is left at the node's own default, 625,000 bytes a second. */
    const val MAX_ALLOCATIONS = 8
    const val MAX_PER_USER = 2
    const val MAX_TCP_CONNECTIONS = 16
    const val BYTES_PER_SECOND = 125_000
    /** Relayed data a day before the relay stops until local midnight (design, 4.3). */
    const val DAILY_LIMIT_BYTES = 2_000_000_000L
    /** Listener ports are picked from here: clear of the usual service ports and
     *  of 49152 to 65535, where the node picks its allocation ports. */
    val LISTEN_PORTS = 20000..39999
}

/** The hosting network: this phone's Wi-Fi IPv4 address and its router's. */
internal data class RelayNetwork(val bind: String, val gateway: String)

/** This device's own relay as its listener on the local network. */
internal data class LocalTurn(val host: String, val port: Int)

internal object RelayConfig {
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val IPV4 = Regex("^([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})$")
    private val DNS_NAME = Regex("^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val LOCAL_NAMES = listOf("local", "localhost", "internal", "invalid", "test")

    private fun octets(text: String): List<Int>? {
        val match = IPV4.matchEntire(text) ?: return null
        val parts = match.groupValues.drop(1)
        if (parts.any { it.length > 1 && it.startsWith('0') }) return null
        return parts.map(String::toInt).takeIf { values -> values.all { it in 0..255 } }
    }

    /**
     * A concrete IPv4 address the node will take for `--bind` or `--gateway`:
     * not unspecified (`0/8`), loopback, link-local, multicast or reserved
     * (`224/3`), or broadcast.
     */
    fun usableAddress(text: String): Boolean {
        val (a, b) = octets(text) ?: return false
        return a != 0 && a != 127 && a < 224 && !(a == 169 && b == 254)
    }

    /**
     * One `--control-relay` value: a `wss://` URL on a DNS name, no login,
     * query or fragment, no IP literal or local name, normalised as
     * `SpryControlEndpoint` writes it, or null.
     */
    fun controlRelay(value: String): String? = try {
        require(value.length <= 256)
        val url = URI(value.trim())
        val host = requireNotNull(url.host).lowercase()
        require(url.scheme.equals("wss", ignoreCase = true) && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null)
        require(DNS_NAME.matches(host) && !Regex("^[0-9.]+$").matches(host) && LOCAL_NAMES.none { host == it || host.endsWith(".$it") })
        require(url.port == -1 || url.port in 1..65535)
        val out = "wss://$host" + (if (url.port == -1 || url.port == 443) "" else ":${url.port}") + url.rawPath.ifEmpty { "/" }
        require(out.length <= 256 && out.none { it.isWhitespace() || it.isISOControl() })
        out
    } catch (_: Exception) { null }

    /** 1 to 4 distinct control relays, normalised, in order; throws otherwise. */
    fun controlRelayList(urls: List<String>): List<String> {
        require(urls.size in 1..4) { "Invalid call relay settings." }
        val out = urls.map { requireNotNull(controlRelay(it)) { "Invalid call relay settings." } }
        require(out.distinct().size == out.size) { "Invalid call relay settings." }
        return out
    }

    /** Up to four of the person's relays that can carry `spry:` control. */
    fun pickControlRelays(urls: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (url in urls) {
            if (out.size == 4) break
            val normal = controlRelay(url) ?: continue
            if (normal !in out) out += normal
        }
        return out
    }

    /**
     * The options for one run, exactly as the desktop builds them, without
     * `--exit-on-stdin-close` (the library refuses it; its stop call is the
     * lifetime) and without a leading `supervised` (the library's options are
     * what follows it: spry-turn docs/embedding.md, `spry_relay.h`). Every
     * network value is explicit; `--gateway` is the router from
     * `LinkProperties`.
     */
    fun args(pairingPublicKey: String, stateFile: String, controlRelays: List<String>, network: RelayNetwork, listenPort: Int): List<String> {
        require(HEX64.matches(pairingPublicKey) && stateFile.startsWith("/") && usableAddress(network.bind) && usableAddress(network.gateway) &&
            network.bind != network.gateway && listenPort in RelayCaps.LISTEN_PORTS) { "Invalid call relay settings." }
        val relays = controlRelayList(controlRelays)
        return listOf("--map-ports", "--gateway", network.gateway,
            "--pairing-key", pairingPublicKey, "--state-file", stateFile) +
            relays.flatMap { listOf("--control-relay", it) } +
            listOf("--bind", network.bind, "--listen-port", listenPort.toString(),
                "--max-allocations", RelayCaps.MAX_ALLOCATIONS.toString(), "--max-per-user", RelayCaps.MAX_PER_USER.toString(),
                "--max-tcp-connections", RelayCaps.MAX_TCP_CONNECTIONS.toString(), "--bytes-per-second", RelayCaps.BYTES_PER_SECOND.toString())
    }

    /** The node's only setting. The library never reads the process
     *  environment, so nothing else can apply. Holds the secret: never log it. */
    fun environment(turnSecret: String): List<String> {
        require(HEX64.matches(turnSecret)) { "Invalid call relay settings." }
        return listOf("SPRY_TURN_SECRET=$turnSecret")
    }

    /** The TURN URLs for this device's own relay on its local network, or a
     *  throw for an address that cannot be one. The desktop's `localTurnUrls`. */
    fun localTurnUrls(local: LocalTurn): List<String> {
        require(usableAddress(local.host) && local.port in 1024..65535) { "Invalid Spry relay answer." }
        return listOf("turn:${local.host}:${local.port}?transport=udp", "turn:${local.host}:${local.port}?transport=tcp")
    }
}

/** Plain words for the node's reasons a network cannot host: the settings
 *  spec's table 6.5, with the device named "phone". The desktop keeps the same
 *  sentences with "computer". */
internal val CANNOT_HOST_TEXT: Map<String, String> = mapOf(
    "no-gateway" to "This phone has no route to the internet.",
    "vpn" to "A VPN is on. People can't reach a relay behind it.",
    "wrong-interface" to "This phone reaches its router through another connection. It tries again once the network settles.",
    "no-answer" to "Your router didn't allow automatic setup. Turn on UPnP in its settings, or ask whoever runs your network.",
    "refused" to "Your router refused to open what the relay needs.",
    "failed" to "Your router couldn't open what the relay needs.",
    "carrier-grade-nat" to "Your internet provider shares one address between many customers, so nothing outside can reach this phone.",
    "double-nat" to "There's a second router between you and the internet. Automatic setup can't open it.",
    "unusable-address" to "Your router reported an internet address people can't reach.",
    "too-few-ports" to "Your router opened too little for the relay to work.",
    "record" to "The relay couldn't save its list of router openings, so it gave them back.",
    "expired" to "Your router dropped the relay and wouldn't take it back.",
)

/** What is said of a reason the node worded in a way this build does not know. */
internal const val CANNOT_HOST_UNKNOWN = "This network can't host a call relay."
