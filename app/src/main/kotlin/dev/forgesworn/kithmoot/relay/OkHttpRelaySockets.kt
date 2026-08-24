package dev.forgesworn.kithmoot.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The production socket factory: OkHttp websockets.
 *
 * The ping interval is the important setting. Relays sit behind load balancers
 * and mobile NAT tables that quietly drop an idle connection without telling
 * either end, and a room can be idle for minutes at a time. Pinging turns a
 * silently dead socket into a failure the pool can act on.
 */
class OkHttpRelaySockets(
    private val client: OkHttpClient = defaultClient(),
) : RelaySocketFactory {

    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, Adapter(listener))
        return object : RelaySocket {
            override fun send(text: String) {
                socket.send(text)
            }

            override fun close() {
                socket.close(1000, null)
            }
        }
    }

    /** Collapses OkHttp's four terminal callbacks into the one the pool wants. */
    private class Adapter(private val listener: RelaySocketListener) : WebSocketListener() {
        private var finished = false

        override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish("closed: $code $reason")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            finish("failed: ${t.message}")

        private fun finish(reason: String) {
            if (finished) return
            finished = true
            listener.onClosed(reason)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
