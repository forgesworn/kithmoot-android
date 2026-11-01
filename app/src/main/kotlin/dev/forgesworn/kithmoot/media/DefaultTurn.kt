package dev.forgesworn.kithmoot.media

import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * ForgeSworn's own TURN relay, the default again from 1 November 2026 (as the
 * web's `TURN_CREDENTIAL_ENDPOINT`). A call that has no relay of its own, from
 * the person, their Spry node or a public relay they chose, asks
 * `https://kithmoot.forgesworn.dev/turn` for a short-lived login, bounded at
 * four seconds. Any failure leaves the call on STUN alone: this never blocks
 * or fails a call.
 *
 * A login is kept until shortly before it lapses and then asked for again, so
 * calls close together share one request. Its addresses must name this host
 * and nothing else, so a bad answer can never send a login elsewhere.
 */
internal object DefaultTurn {
    internal const val HOST = "kithmoot.forgesworn.dev"
    internal const val ENDPOINT = "https://$HOST/turn"
    internal const val FETCH_LIMIT_MS = 4_000L

    /** As the web: a shorter or longer lease is refused rather than trusted. */
    internal const val MIN_TTL_SECONDS = 3_600L
    internal const val MAX_TTL_SECONDS = 86_400L

    /** A kept login is reused until this long before it lapses, and a call on one asks again this long before. */
    internal const val RENEW_BEFORE_MS = 15 * 60 * 1000L

    private val client = OkHttpClient.Builder().callTimeout(FETCH_LIMIT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    /** A login and when it lapses, on the [SystemClock.elapsedRealtime] clock. */
    internal class Login(val relay: CallRelayConfig, val expiresAtMs: Long) {
        override fun toString() = "DefaultTurn.Login(redacted)"
    }

    @Volatile internal var kept: Login? = null

    /** The kept login when it has more than [RENEW_BEFORE_MS] left, else null. */
    internal fun keptLogin(nowMs: Long = SystemClock.elapsedRealtime()): Login? =
        kept?.takeIf { it.expiresAtMs - nowMs > RENEW_BEFORE_MS }

    internal fun forget() { kept = null }

    /** A login for a call: the kept one, else a fresh one. Null on any failure, within [FETCH_LIMIT_MS]. */
    suspend fun obtain(): Login? = keptLogin() ?: renew()

    /** A fresh login, kept for the next call. Null on any failure, and then the kept one is left alone. */
    suspend fun renew(): Login? = fetch(client, ENDPOINT, FETCH_LIMIT_MS)?.also { kept = it }

    /**
     * The login, or null - and null within [limitMs], whatever the network is
     * doing. `callTimeout` alone does not promise that: a blocking `execute()`
     * stuck in the system DNS lookup does not return until the lookup does,
     * and a phone answering a ring from the lock screen measured 38 seconds
     * that way. Enqueued and awaited instead, so the wait ends on time and the
     * lookup finishes, or not, on OkHttp's own thread.
     */
    internal suspend fun fetch(client: OkHttpClient, url: String, limitMs: Long): Login? =
        withTimeoutOrNull(limitMs) {
            val begun = SystemClock.elapsedRealtime()
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
                                if (text.length > 16_384) null else parse(text, begun)
                            }
                        }.getOrNull()
                        waiting.resume(value)
                    }
                })
            }
        }

    /** The answer, or null when it is malformed, names another host, or has a lease outside the accepted range. */
    internal fun parse(text: String, issuedAtMs: Long): Login? = runCatching {
        val body = Json.parseToJsonElement(text).jsonObject
        val urls = body.getValue("urls").jsonArray.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }
        require(urls.isNotEmpty() && urls.size <= 8)
        // Credentials belong to this operator only; never forward them to a URL
        // supplied by a room or to a different host returned by a bad response.
        val allowed = Regex("^turns?:${Regex.escape(HOST)}:(3478|5349)(\\?transport=(udp|tcp))?$", RegexOption.IGNORE_CASE)
        require(urls.all { allowed.matches(it) })
        val ttl = (body.getValue("ttl") as JsonPrimitive).content.toLong()
        require(ttl in MIN_TTL_SECONDS..MAX_TTL_SECONDS)
        val username = body.getValue("username").jsonPrimitive.content
        val credential = body.getValue("credential").jsonPrimitive.content
        val relay = requireNotNull(CallRelayConfig.parse(urls.joinToString(","), username, credential))
        Login(relay, issuedAtMs + ttl * 1000)
    }.getOrNull()
}
