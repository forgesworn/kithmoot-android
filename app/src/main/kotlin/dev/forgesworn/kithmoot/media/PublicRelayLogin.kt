package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.PublicCallRelayAdvert
import dev.forgesworn.kithmoot.protocol.PublicRelayAdvert
import dev.forgesworn.kithmoot.protocol.PublicRelayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An hour-long login issued by a public call relay's operator. Held in memory
 * for one call and dropped when it ends; never saved, logged, put in a room
 * or sent anywhere but the relay it came from. [toString] holds nothing secret.
 */
internal class PublicRelayLogin(
    /** The transport key of the advert it was requested through. */
    val key: String,
    val host: String,
    /** Only URLs the signed advert named. */
    val urls: List<String>,
    val username: String,
    val credential: String,
    /** Seconds since the epoch. */
    val expiresAt: Long,
) {
    override fun toString() = "PublicRelayLogin(redacted)"
}

/** What a login request came to. Nothing the operator said is kept as text. */
internal sealed interface PublicRelayLoginResult {
    class Issued(val login: PublicRelayLogin) : PublicRelayLoginResult {
        override fun toString() = "Issued(redacted)"
    }
    /** The operator's limiter said 429: worth retrying before the login runs out. */
    data object Busy : PublicRelayLoginResult
    data object Failed : PublicRelayLoginResult
}

/**
 * Asks a verified advert's operator for a public login. Separate from Spry's
 * client on purpose: its own connection pool, no proxy (a proxy would resolve
 * the name and skip the address check), no redirects, no cookies, no cache, a
 * 4 second call and a 4 KiB body at most. Every returned URL must be one the
 * signed advert named. It sends `POST` with body exactly `{"v":1}`, no
 * `Origin`, `Referer` or cookies, and OkHttp's own `User-Agent`.
 *
 * [dns] decides which addresses may be connected to. Production passes
 * [PublicOnlyDns], which refuses private, loopback and reserved ones; tests
 * pass their own to reach a local server. The extra [customise] hook is for
 * tests too.
 */
internal class PublicRelayLoginClient internal constructor(
    dns: Dns,
    customise: OkHttpClient.Builder.() -> Unit,
    private val clock: () -> Long,
) {
    constructor() : this(PublicOnlyDns(), {}, { System.currentTimeMillis() / 1000 })

    internal val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        // A POST that is retried on another address is a second allocation.
        .retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .dns(dns)
        .apply(customise)
        .build()

    /** Drops idle connections. Call when the call ends. */
    fun evictAll() { client.connectionPool.evictAll() }

    /** Evicts connections and stops the client's threads. Not reusable afterwards. */
    fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Never throws except for cancellation; any failure is [PublicRelayLoginResult.Failed]. */
    suspend fun fetch(advert: PublicRelayAdvert): PublicRelayLoginResult {
        val offer = advert.offer
        val host = advert.host
        // Connect-time hostname rules first: nothing is sent to a name that fails them.
        if (advert.status != PublicRelayStatus.Open || offer == null || host == null || !PublicCallRelayAdvert.hostAllowed(host)) {
            return PublicRelayLoginResult.Failed
        }
        return try {
            val request = Request.Builder().url(offer.credentials).post(BODY.toRequestBody(JSON)).build()
            val response = client.newCall(request).await()
            try {
                withContext(Dispatchers.IO) { read(advert, offer.urls, host, response) }
            } finally {
                response.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PublicRelayLoginResult.Failed
        }
    }

    private fun read(advert: PublicRelayAdvert, advertised: List<String>, host: String, response: Response): PublicRelayLoginResult {
        if (response.code == 429) return PublicRelayLoginResult.Busy
        if (response.code != 200 || response.isRedirect) return PublicRelayLoginResult.Failed
        if (!JSON_TYPE.matches(response.header("Content-Type").orEmpty())) return PublicRelayLoginResult.Failed
        val body = response.body ?: return PublicRelayLoginResult.Failed
        if (body.contentLength() > LIMIT) return PublicRelayLoginResult.Failed
        // Stream with a cap: stop at one byte over, whatever the operator declared.
        val source = body.source()
        val buffer = Buffer()
        while (buffer.size <= LIMIT) {
            if (source.read(buffer, LIMIT + 1 - buffer.size) == -1L) break
        }
        if (buffer.size > LIMIT) return PublicRelayLoginResult.Failed
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
        val value = Json.parseToJsonElement(text) as? JsonObject ?: return PublicRelayLoginResult.Failed

        val at = clock()
        val expiry = value["expiresAt"] as? JsonPrimitive
        if (expiry == null || expiry.isString || !DIGITS.matches(expiry.content)) return PublicRelayLoginResult.Failed
        val expiresAt = expiry.content.toLongOrNull() ?: return PublicRelayLoginResult.Failed
        // An hour-long login, with a minute of clock slack.
        if (expiresAt <= at || expiresAt > at + LOGIN_SECONDS + SLACK_SECONDS) return PublicRelayLoginResult.Failed
        val username = (value["username"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return PublicRelayLoginResult.Failed
        val name = USERNAME.matchEntire(username) ?: return PublicRelayLoginResult.Failed
        if (name.groupValues[1] != expiresAt.toString()) return PublicRelayLoginResult.Failed
        val credential = (value["credential"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return PublicRelayLoginResult.Failed
        if (credential.isEmpty() || credential.length > 64 || !BASE64.matches(credential)) return PublicRelayLoginResult.Failed
        val urls = value["urls"] as? JsonArray ?: return PublicRelayLoginResult.Failed
        // A URL the signed advert did not name is dropped, never contacted.
        val narrowed = urls.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it in advertised }.distinct()
        if (narrowed.isEmpty()) return PublicRelayLoginResult.Failed
        return PublicRelayLoginResult.Issued(PublicRelayLogin(advert.publicKey, host, narrowed, username, credential, expiresAt))
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { waiting ->
        waiting.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { waiting.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { if (waiting.isActive) waiting.resume(response) else response.close() }
        })
    }

    companion object {
        const val CALL_TIMEOUT_MS = 4000L
        const val LIMIT = 4096L
        const val LOGIN_SECONDS = 3600L
        const val SLACK_SECONDS = 60L

        private val BODY = "{\"v\":1}".toByteArray(Charsets.UTF_8)
        // Exactly `application/json`: the string overload of toRequestBody would add a charset.
        private val JSON = "application/json".toMediaType()
        private val JSON_TYPE = Regex("application/json(?:\\s*;.*)?", RegexOption.IGNORE_CASE)
        // A canonical integer only: no sign, leading zero, fraction or exponent.
        private val DIGITS = Regex("0|[1-9][0-9]{0,15}")
        private val USERNAME = Regex("([0-9]+):pub-[0-9a-f]{24}")
        private val BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
    }
}
