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
        val adapter = Adapter(listener)
        val socket = client.newWebSocket(request, adapter)
        return object : RelaySocket {
            override fun send(text: String) {
                socket.send(text)
            }

            // A socket still waiting on its upgrade has nothing to send a
            // close frame over; only cancelling ends the attempt.
            override fun close() {
                if (adapter.opened) socket.close(1000, null) else socket.cancel()
            }
        }
    }

    /** Collapses OkHttp's four terminal callbacks into the one the pool wants. */
    private class Adapter(private val listener: RelaySocketListener) : WebSocketListener() {
        private var finished = false
        @Volatile var opened = false
            private set

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            listener.onOpen()
        }

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

        /**
         * For the closed-app call listener. Every ping wakes the phone's
         * radio, and at 20 s across three relays that is nine wake-ups a
         * minute for a socket that is otherwise silent. 90 s keeps a dead
         * socket found and replaced inside the bell's 120 s lifetime, so a
         * reconnect's `since` still catches a bell rung while it was down.
         */
        fun backgroundClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(BACKGROUND_PING_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        const val BACKGROUND_PING_SECONDS = 90L
    }
}
