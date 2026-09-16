package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/** The native app uses the same short-lived TURN service as the hosted PWA. */
internal object CallIceServers {
    private const val HOST = "kithmoot.forgesworn.dev"
    private val client = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun resolve(): List<PeerConnection.IceServer> = withContext(Dispatchers.IO) {
        val stun = PeerConnection.IceServer.builder("stun:$HOST:3478").createIceServer()
        val turn = runCatching {
            client.newCall(Request.Builder().url("https://$HOST/turn").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.byteStream()?.readNBytes(16_385)?.toString(Charsets.UTF_8) ?: return@use null
                if (text.length > 16_384) return@use null
                parse(text)?.let { value -> PeerConnection.IceServer.builder(value.urls)
                    .setUsername(value.username).setPassword(value.credential).createIceServer() }
            }
        }.getOrNull()
        listOfNotNull(stun, turn)
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
