package dev.forgesworn.kithmoot.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.digests.SHA3Digest
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Transport rules for the Android half of Vennel anonymous mode. This is a
 * carrier boundary, not a VPN detector or an anonymity claim: a caller must
 * supply a dialler that actually sends through Tor.
 */
object TorOnlyRelayUrls {
    private val onion = Regex("^([a-z2-7]{56})\\.onion$")
    private const val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    private val checksumPrefix = ".onion checksum".toByteArray(Charsets.US_ASCII)

    private fun decodeV3Address(value: String): ByteArray {
        var bits = 0
        var accumulator = 0
        val output = ArrayList<Byte>(35)
        for (character in value) {
            val digit = alphabet.indexOf(character)
            require(digit >= 0) { "Invalid v3 onion service address." }
            accumulator = (accumulator shl 5) or digit
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output += ((accumulator ushr bits) and 0xff).toByte()
            }
        }
        require(bits == 0 && output.size == 35) { "Invalid v3 onion service address." }
        return output.toByteArray()
    }

    /** Refuse v2, look-alike and checksum-invalid onion names before dialing. */
    fun assertV3OnionHostname(hostname: String): String {
        val match = onion.matchEntire(hostname.lowercase()) ?: throw IllegalArgumentException(
            "Tor-only mode accepts exact v3 .onion hostnames only.",
        )
        val decoded = decodeV3Address(match.groupValues[1])
        val version = decoded[34].toInt() and 0xff
        require(version == 3) { "Invalid v3 onion service version." }
        val digestInput = checksumPrefix + decoded.copyOfRange(0, 32) + byteArrayOf(version.toByte())
        val digest = ByteArray(32)
        SHA3Digest(256).apply { update(digestInput, 0, digestInput.size); doFinal(digest, 0) }
        require(decoded[32] == digest[0] && decoded[33] == digest[1]) { "Invalid v3 onion service checksum." }
        return "${match.groupValues[1]}.onion"
    }

    /** Accept an onion WebSocket endpoint, never a clearnet or credential URL. */
    fun normalise(value: String): String {
        require(value.length in 1..2048) { "Relay URL is invalid." }
        val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("Relay URL is invalid.") }
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Relay URL is invalid.")
        require(scheme == "ws" || scheme == "wss") { "Tor-only relays must use ws:// or wss://." }
        require(uri.userInfo == null && uri.fragment == null) { "Relay URLs cannot contain credentials or fragments." }
        val host = assertV3OnionHostname(uri.host ?: throw IllegalArgumentException("Relay URL is invalid."))
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "$scheme://$host$port$path$query"
    }

    /** No direct media/STUN/TURN route is a valid degradation of Tor-only mode. */
    fun assertRoomTransport(relays: List<String>, iceUrls: List<String>): List<String> {
        require(relays.isNotEmpty()) { "Tor-only mode needs at least one onion relay." }
        require(iceUrls.isEmpty()) { "Tor-only mode disables direct media, STUN and TURN." }
        val normalised = relays.map(::normalise)
        require(normalised.distinct().size == normalised.size) { "That relay is already in the list." }
        return normalised
    }
}

/** The native Tor/Orbot carrier must implement this. It is intentionally not OkHttp. */
fun interface TorRelaySocketFactory {
    fun openTor(url: String, listener: RelaySocketListener): RelaySocket
}

/**
 * Enforces onion validation immediately before dialing. There is deliberately
 * no fallback to [OkHttpRelaySockets]: using the Android default stack here
 * would turn a carrier failure into a clearnet connection.
 */
class TorOnlyRelaySockets(private val tor: TorRelaySocketFactory) : RelaySocketFactory {
    override fun open(url: String, listener: RelaySocketListener): RelaySocket =
        tor.openTor(TorOnlyRelayUrls.normalise(url), listener)
}

/**
 * The concrete Android carrier for a locally running Orbot. Guardian Project
 * documents Orbot's loopback HTTP proxy at port 8118; OkHttp tunnels the
 * WebSocket CONNECT through that proxy and never consults the device's normal
 * proxy selector. An unavailable proxy reports a failed relay socket, rather
 * than falling back to [OkHttpRelaySockets].
 */
class OrbotTorRelaySockets(
    private val client: OkHttpClient = defaultClient(),
) : RelaySocketFactory {
    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        val request = Request.Builder().url(TorOnlyRelayUrls.normalise(url)).build()
        val socket = client.newWebSocket(request, Adapter(listener))
        return object : RelaySocket {
            override fun send(text: String) { socket.send(text) }
            override fun close() { socket.close(1000, null) }
        }
    }

    private class Adapter(private val listener: RelaySocketListener) : WebSocketListener() {
        private var finished = false

        override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish("closed: $code $reason")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish("failed: ${t.message}")

        private fun finish(reason: String) {
            if (finished) return
            finished = true
            listener.onClosed(reason)
        }
    }

    companion object {
        const val ORBOT_PROXY_HOST = "127.0.0.1"
        const val ORBOT_HTTP_PROXY_PORT = 8118

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(ORBOT_PROXY_HOST, ORBOT_HTTP_PROXY_PORT)))
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
