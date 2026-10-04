package dev.forgesworn.kithmoot.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [UpdateNetwork] over OkHttp. Redirects are not followed, as elsewhere in the
 * app: the manifest and the APK live at fixed addresses, and a redirect would
 * be the server choosing somewhere else to send us.
 */
class OkHttpUpdateNetwork(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) : UpdateNetwork {

    override suspend fun fetch(url: String, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        open(url) { response ->
            val body = response.body ?: throw IOException("$url has no body")
            if (body.contentLength() > limit) throw IOException("$url is too large")
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > limit) throw IOException("$url is too large")
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray()
        }
    }

    override suspend fun stream(url: String, sink: (ByteArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        open(url) { response ->
            val body = response.body ?: throw IOException("$url has no body")
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink(buffer, read)
                }
            }
        }
    }

    /** Runs [read] on a 200 answer from [url], cancelling the request if the coroutine is cancelled. */
    private suspend fun <T> open(url: String, read: suspend (Response) -> T): T {
        val call = client.newCall(Request.Builder().url(url).build())
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use { response ->
                if (response.code != 200) throw IOException("$url answered ${response.code}")
                read(response)
            }
        } finally {
            cancel?.dispose()
        }
    }
}
