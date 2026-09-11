package dev.forgesworn.kithmoot.relay

import java.net.URI

/** Opens a socket through Link using the opaque route id held by its vault. */
fun interface LinkRelaySocketFactory {
    fun open(url: String, routeId: String, listener: RelaySocketListener): RelaySocket
}

/** Resolves only consents active for the account and room that own this pool. */
fun interface ActiveLinkRoute {
    fun routeId(url: String): String?
}

/**
 * Keeps ordinary relays on OkHttp while making Link-shaped addresses fail
 * closed. In particular, an absent consent, changed URL or malformed Link URL
 * can never fall through into DNS.
 */
class HybridRelaySockets(
    private val publicSockets: RelaySocketFactory,
    private val linkSockets: LinkRelaySocketFactory,
    private val activeRoute: ActiveLinkRoute,
) : RelaySocketFactory {
    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        if (!LinkRelayAddress.looksLikeLink(url)) return publicSockets.open(url, listener)
        val canonical = LinkRelayAddress.canonical(url)
            ?: throw IllegalArgumentException("The Link relay address is not canonical")
        val routeId = activeRoute.routeId(canonical)
            ?: throw IllegalStateException("This Link relay has no active consent")
        return linkSockets.open(canonical, routeId, listener)
    }
}

internal object LinkRelayAddress {
    private val alphabet = Regex("[a-z2-7]{51}[aq]")
    private val candidateAlphabet = Regex("[A-Za-z2-7]{52}")

    /** Broad enough to catch non-canonical variants before OkHttp can see them. */
    fun looksLikeLink(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("ws", "wss")) return false
        return uri.host?.matches(candidateAlphabet) == true
    }

    fun canonical(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.matches(alphabet)) return null
        val expected = "ws://$host/events"
        return expected.takeIf { value == it }
    }

    fun canonicalForNode(nodeId: String): String {
        require(nodeId.matches(Regex("[0-9a-f]{64}")))
        val bytes = nodeId.chunked(2).map { it.toInt(16).toByte() }
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        var bits = 0; var value = 0; val out = StringBuilder(52)
        for (byte in bytes) {
            value = (value shl 8) or (byte.toInt() and 0xff); bits += 8
            while (bits >= 5) { out.append(alphabet[(value shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) out.append(alphabet[(value shl (5 - bits)) and 31])
        return "ws://$out/events"
    }
}
