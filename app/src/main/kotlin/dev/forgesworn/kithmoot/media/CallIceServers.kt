package dev.forgesworn.kithmoot.media

import android.os.SystemClock
import android.util.Log
import dev.forgesworn.kithmoot.ui.JOIN_LOG
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/** The native app uses the same short-lived TURN service as the hosted PWA. */
internal object CallIceServers {
    private const val HOST = "kithmoot.forgesworn.dev"
    private const val TURN_URL = "https://$HOST/turn"
    private const val FETCH_LIMIT_MS = 4_000L
    private val client = OkHttpClient.Builder().callTimeout(FETCH_LIMIT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun resolve(): List<PeerConnection.IceServer> {
        val stun = PeerConnection.IceServer.builder("stun:$HOST:3478").createIceServer()
        val begun = SystemClock.elapsedRealtime()
        val turn = fetchTurn(client, TURN_URL, FETCH_LIMIT_MS)?.let { value ->
            PeerConnection.IceServer.builder(value.urls)
                .setUsername(value.username).setPassword(value.credential).createIceServer()
        }
        Log.i(JOIN_LOG, "turn fetch ${if (turn != null) "ok" else "none"} in ${SystemClock.elapsedRealtime() - begun}ms")
        return listOfNotNull(stun, turn)
    }

    /**
     * The TURN credential, or null - and null within [limitMs], whatever the
     * network is doing.
     *
     * `callTimeout` alone did not promise that. It cancels the call, but a
     * blocking `execute()` sitting in the system DNS lookup does not return
     * until the lookup does, and on a phone answering a ring from the lock
     * screen that measured 38 seconds with nothing to join the call with.
     * Enqueued and awaited instead, so the wait ends on time and the lookup
     * finishes, or not, on OkHttp's own thread. Without TURN the call still
     * starts on STUN.
     */
    internal suspend fun fetchTurn(client: OkHttpClient, url: String, limitMs: Long): Credential? =
        withTimeoutOrNull(limitMs) {
            suspendCancellableCoroutine { waiting ->
                val call = client.newCall(Request.Builder().url(url).build())
                waiting.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = waiting.resume(null)
                    override fun onResponse(call: Call, response: Response) {
                        val value = runCatching {
                            response.use {
                                if (!it.isSuccessful) return@use null
                                val text = it.body?.byteStream()?.readNBytes(16_385)?.toString(Charsets.UTF_8) ?: return@use null
                                if (text.length > 16_384) null else parse(text)
                            }
                        }.getOrNull()
                        waiting.resume(value)
                    }
                })
            }
        }

    internal data class Credential(val urls: List<String>, val username: String, val credential: String)
    internal fun parse(text: String): Credential? = runCatching {
        val body = Json.parseToJsonElement(text).jsonObject
        val urls = body.getValue("urls").jsonArray.map { it.jsonPrimitive.content }
        require(urls.isNotEmpty() && urls.size <= 8)
        // Credentials belong to this operator only; never forward them to a URL
        // supplied by a room or to a different host returned by a bad response.
        val allowed = Regex("^turns?:${Regex.escape(HOST)}:(3478|5349)(\\?transport=(udp|tcp))?$", RegexOption.IGNORE_CASE)
        require(urls.all { allowed.matches(it) })
        val username = body.getValue("username").jsonPrimitive.content
        val credential = body.getValue("credential").jsonPrimitive.content
        require(username.isNotBlank() && username.length <= 1024 && credential.isNotBlank() && credential.length <= 1024)
        Credential(urls, username, credential)
    }.getOrNull()
}
