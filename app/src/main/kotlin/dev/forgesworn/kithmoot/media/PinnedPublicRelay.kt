package dev.forgesworn.kithmoot.media

import java.net.Inet6Address
import java.net.InetAddress

/**
 * A discovered relay's login with its address fixed in advance: [urls] carry
 * a checked public IP address and [host] goes to libwebrtc through
 * `IceServer.Builder.setHostname`, so libwebrtc does no lookup of its own and
 * `turns:` still verifies the certificate against the name. [toString] holds
 * nothing secret or identifying.
 */
internal class PinnedPublicRelay(
    val key: String,
    val host: String,
    val urls: List<String>,
    val username: String,
    val credential: String,
    val expiresAt: Long,
) {
    override fun toString() = "PinnedPublicRelay(redacted)"
}

internal object PublicRelayPinning {
    private val URL = Regex("^(turns?):([^:?\\[\\]]+)(:[0-9]{1,5})?(\\?.*)?$", RegexOption.IGNORE_CASE)

    /**
     * Resolves [PublicRelayLogin.host] with [lookup] (production: [PublicOnlyDns],
     * which refuses the whole answer if any address is not public) and rewrites
     * every URL to the first address. Null when the name does not resolve, an
     * address is refused, or a URL is not for that host. Never throws; the
     * failure carries no text, since a resolver's message may name the host.
     */
    fun pin(login: PublicRelayLogin, lookup: (String) -> List<InetAddress> = PublicOnlyDns()::lookup): PinnedPublicRelay? {
        val address = try { lookup(login.host).firstOrNull() } catch (_: Exception) { null } ?: return null
        if (!PublicAddress.isPublic(address)) return null
        val literal = literal(address)
        val urls = login.urls.map { url -> rewrite(url, login.host, literal) ?: return null }
        if (urls.isEmpty()) return null
        return PinnedPublicRelay(login.key, login.host, urls, login.username, login.credential, login.expiresAt)
    }

    /** An address as it goes in a TURN URL: IPv6 in brackets, with no zone. */
    internal fun literal(address: InetAddress): String {
        val text = address.hostAddress.orEmpty().substringBefore('%')
        return if (address is Inet6Address) "[$text]" else text
    }

    /** [url] with its host, which must be [host], replaced by [literal]. */
    internal fun rewrite(url: String, host: String, literal: String): String? {
        val match = URL.matchEntire(url) ?: return null
        val (scheme, name, port, query) = match.destructured
        if (!name.equals(host, ignoreCase = true)) return null
        return "$scheme:$literal$port$query"
    }
}
