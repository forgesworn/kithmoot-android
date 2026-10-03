package dev.forgesworn.kithmoot.relay

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.digests.SHA3Digest
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import javax.net.SocketFactory
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
 * documents Orbot's loopback HTTP proxy at port 8118, and every relay reaches
 * it as a CONNECT to the onion, never consulting the device's normal proxy
 * selector. OkHttp does that itself only for `wss://`: a `ws://` URL through an
 * HTTP proxy goes as a plain proxied GET, which never reaches the onion. So a
 * `ws://` relay dials through [OrbotConnectTunnel] instead. An unavailable or
 * refusing proxy reports a failed relay socket, rather than falling back to
 * [OkHttpRelaySockets].
 */
class OrbotTorRelaySockets(
    private val client: OkHttpClient = defaultClient(),
) : RelaySocketFactory {
    private val plainClient: OkHttpClient by lazy { OrbotConnectTunnel.plainClient(client) }

    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        val relay = TorOnlyRelayUrls.normalise(url)
        val request = Request.Builder().url(relay).build()
        val adapter = Adapter(listener)
        val socket = (if (relay.startsWith("ws://")) plainClient else client).newWebSocket(request, adapter)
        return object : RelaySocket {
            override fun send(text: String) { socket.send(text) }
            // A socket still waiting on its upgrade has nothing to send a
            // close frame over; only cancelling ends the attempt.
            override fun close() { if (adapter.opened) socket.close(1000, null) else socket.cancel() }
        }
    }

    private class Adapter(private val listener: RelaySocketListener) : WebSocketListener() {
        private var finished = false
        @Volatile var opened = false
            private set

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            listener.onOpen()
        }
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

/**
 * A `ws://` onion relay through Orbot's HTTP proxy, as a CONNECT tunnel.
 *
 * The client it builds has no proxy of its own, so two pieces keep every byte
 * on the proxy. [dns] answers only for v3 onion names, and with a placeholder
 * address that carries the name and is never looked up. [TunnelSocket] ignores
 * that address: it dials only the proxy and asks it, by name, for the onion.
 * Anything that is not an onion is refused before a socket opens.
 */
internal object OrbotConnectTunnel {
    private val placeholder = byteArrayOf(0, 0, 0, 0)

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val onion = try { TorOnlyRelayUrls.assertV3OnionHostname(hostname) }
                catch (_: IllegalArgumentException) { throw UnknownHostException("Tor-only relays resolve only v3 onion names.") }
            return listOf(InetAddress.getByAddress(onion, placeholder))
        }
    }

    /** [base] with its HTTP proxy replaced by a CONNECT tunnel to that same proxy. */
    fun plainClient(base: OkHttpClient): OkHttpClient {
        val proxy = requireNotNull(base.proxy) { "The Orbot carrier needs its proxy." }
        require(proxy.type() == Proxy.Type.HTTP) { "The Orbot carrier needs an HTTP proxy." }
        val address = proxy.address() as InetSocketAddress
        return base.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(dns)
            .socketFactory(Factory(address, base.connectTimeoutMillis, base.readTimeoutMillis))
            .build()
    }

    private class Factory(private val proxy: InetSocketAddress, private val connectMs: Int, private val replyMs: Int) : SocketFactory() {
        override fun createSocket(): Socket = TunnelSocket(proxy, connectMs, replyMs)
        // OkHttp creates sockets unconnected; a connected one would skip the tunnel.
        override fun createSocket(host: String?, port: Int): Socket = throw IOException("Unsupported.")
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw IOException("Unsupported.")
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw IOException("Unsupported.")
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw IOException("Unsupported.")
    }

    /** Dials the proxy whatever it is asked for, then CONNECTs to the onion it was asked for by name. */
    private class TunnelSocket(
        private val proxy: InetSocketAddress,
        private val connectMs: Int,
        /**
         * How long Orbot may take to answer the CONNECT: the client's read
         * timeout, as OkHttp gives a `wss://` tunnel. Orbot answers once the
         * onion circuit is built, which can take longer than connecting to
         * it; the relay pool's open timeout and `cancel()` still end the wait.
         */
        private val replyMs: Int,
    ) : Socket() {
        override fun connect(endpoint: SocketAddress?) = connect(endpoint, connectMs)

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Tor-only relays need an onion address.")
            // getHostString, never getHostName: the name must not be looked up.
            val onion = try { TorOnlyRelayUrls.assertV3OnionHostname(target.hostString) }
                catch (_: IllegalArgumentException) { throw IOException("Tor-only relays dial only v3 onion names.") }
            super.connect(proxy, timeout)
            val before = soTimeout
            soTimeout = replyMs
            try {
                val authority = "$onion:${target.port}"
                getOutputStream().apply { write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
                val status = readHead().firstOrNull().orEmpty()
                if (!Regex("^HTTP/1\\.[01] 2\\d\\d( .*)?$").matches(status)) throw IOException("Orbot refused the tunnel: ${status.take(64)}")
            } catch (e: IOException) {
                runCatching { close() }
                throw e
            }
            soTimeout = before
        }

        /** The proxy's reply head, byte by byte, so nothing after it is consumed. */
        private fun readHead(): List<String> {
            val input = getInputStream()
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("Orbot closed the tunnel.")
                if (++total > 8192) throw IOException("Orbot's reply was too long.")
                if (b == '\n'.code) {
                    val done = line.toString().trimEnd('\r')
                    if (done.isEmpty()) return lines
                    lines += done
                    line.clear()
                } else line.append(b.toChar())
            }
        }
    }
}
