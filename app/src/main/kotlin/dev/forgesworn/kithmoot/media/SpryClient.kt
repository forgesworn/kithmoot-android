package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.SpryLink
import dev.forgesworn.kithmoot.protocol.SpryControlEndpoint
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.SpryOffer
import dev.forgesworn.kithmoot.protocol.SPRY_GRANT_SECONDS
import dev.forgesworn.kithmoot.protocol.spryEndpoint
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SpryPairing(val endpoint: String, val id: String, val secretKey: String, val account: String?) {
    override fun toString() = "SpryPairing(redacted)"
}
internal data class SpryPairLink(val endpoint: String, val code: String, val expiresAt: Long) {
    override fun toString() = "SpryPairLink(redacted)"
    companion object {
        fun parse(url: String, now: Long = System.currentTimeMillis() / 1000): SpryPairLink {
            try {
                require(url.length <= 4096)
                val fragment = URI(url).rawFragment ?: error("Missing pairing")
                require(fragment.startsWith("spry=") && fragment.length <= 4096)
                val raw = Json.parseToJsonElement(Base64.getUrlDecoder().decode(fragment.substring(5)).toString(Charsets.UTF_8)).jsonObject
                require(raw.getValue("v").jsonPrimitive.int == 1)
                val endpoint = spryEndpoint(raw.getValue("endpoint").jsonPrimitive.content)
                val code = raw.getValue("code").jsonPrimitive.content
                val expiresAt = raw.getValue("expiresAt").jsonPrimitive.long
                require(Regex("^[A-Za-z0-9_-]{43}$").matches(code) && expiresAt > now && expiresAt <= now + 360)
                return SpryPairLink(endpoint, code, expiresAt)
            } catch (_: Exception) { throw IllegalArgumentException("This Spry pairing link has expired. Open Spry on your node and pair again.") }
        }
    }
}
internal class SpryPairingStore(context: Context) {
    private val storage = EncryptedRoomStorage(context, "kithmoot.spry-pairing.v1", 4096)
    fun read(account: String?): SpryPairing? {
        try {
            val value = storage.read() ?: return null
            val raw = Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject
            val savedAccount = raw["account"]?.jsonPrimitive?.contentOrNull
            if (savedAccount != account) return null
            val id = raw.getValue("id").jsonPrimitive.content
            val key = raw.getValue("secretKey").jsonPrimitive.content
            require(Regex("^[A-Za-z0-9_-]{43}$").matches(id) && Regex("^[a-f0-9]{64}$").matches(key))
            return SpryPairing(spryEndpoint(raw.getValue("endpoint").jsonPrimitive.content), id, key, savedAccount)
        } catch (_: Exception) { throw IllegalArgumentException("The Spry pairing is unavailable. Pair it again from your node.") }
    }
    fun save(pair: SpryPairing) = storage.write(buildJsonObject {
        put("v", 1); put("endpoint", pair.endpoint); put("id", pair.id); put("secretKey", pair.secretKey)
        put("account", pair.account?.let(::JsonPrimitive) ?: JsonNull)
    }.toString().toByteArray(Charsets.UTF_8))
    fun remove() = storage.reset()
}

internal class SpryOfferStore(context: Context) {
    companion object { private val lock = Any() }
    private val storage = EncryptedRoomStorage(context, "kithmoot.spry-rooms.v1", 262_144)
    private fun entries(): MutableMap<String, JsonElement> = runCatching {
        storage.read()?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject.toMutableMap() } ?: mutableMapOf()
    }.getOrDefault(mutableMapOf())
    fun read(participant: String, room: String): SpryOffer? = synchronized(lock) {
        SpryOffer.parse(entries()["$participant:$room"], System.currentTimeMillis() / 1000)
    }
    fun save(participant: String, room: String, offer: SpryOffer) = synchronized(lock) {
        val now = System.currentTimeMillis() / 1000
        val values = entries().filterValues { SpryOffer.parse(it, now) != null }.toMutableMap()
        values["$participant:$room"] = offer.toJson()
        val bounded = values.entries.sortedByDescending { it.value.jsonObject["expiresAt"]?.jsonPrimitive?.long ?: 0 }.take(64).associate { it.toPair() }
        storage.write(JsonObject(bounded).toString().toByteArray(Charsets.UTF_8))
    }
}
internal data class SpryCallCredential(val relay: CallRelayConfig, val expiresAt: Long, val accessExpiresAt: Long, val node: NostrEvent? = null) {
    override fun toString() = "SpryCallCredential(redacted)"
}

internal object SpryClient {
    private val client = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                require(addresses.isNotEmpty() && addresses.all(PublicAddress::isPublic)) { "Spry needs a public address." }
                return addresses
            }
        }).build()
    private suspend fun request(endpoint: String, path: String, body: JsonObject, key: ByteArray): JsonObject = withContext(Dispatchers.Default) {
        try {
            require(path in setOf("/v1/pairings/claim", "/v1/pairings/remove-self", "/v1/grants", "/v1/credentials", "/v1/peers"))
            val origin = spryEndpoint(endpoint)
            require(body.toString().toByteArray(Charsets.UTF_8).size <= 8192)
            if (origin.startsWith("spry:")) return@withContext SpryControl.request(client, origin, path, body, key)
            val url = origin + path
            val text = body.toString()
            val event = Events.sign(key, 27235, System.currentTimeMillis() / 1000,
                listOf(listOf("u", url), listOf("method", "POST"), listOf("payload", Digests.sha256(text.toByteArray(Charsets.UTF_8)).toHex()),
                    listOf("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(Entropy.bytes(32)))), "")
            val request = Request.Builder().url(url).post(text.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Nostr " + Base64.getEncoder().encodeToString(event.toCompactJson().toByteArray(Charsets.UTF_8)))
                .header("Cache-Control", "no-store").build()
            withTimeout(6500) { suspendCancellableCoroutine { waiting ->
                val call = client.newCall(request)
                waiting.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) { waiting.resumeWithException(IOException("Spry could not connect. Check the pairing and try again.")) }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use {
                            require(it.isSuccessful)
                            val input = requireNotNull(it.body).byteStream()
                            val bytes = ByteArray(8193)
                            var length = 0
                            while (length < bytes.size) {
                                val count = input.read(bytes, length, bytes.size - length)
                                if (count < 0) break
                                length += count
                            }
                            require(length <= 8192)
                            Json.parseToJsonElement(String(bytes, 0, length, Charsets.UTF_8)).jsonObject
                        } }
                        result.fold(waiting::resume) { waiting.resumeWithException(IOException("Spry access is unavailable. Check the pairing and try again.")) }
                    }
                })
            } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { throw IOException("Spry could not connect. Check the pairing and try again.") }
    }
    suspend fun pair(link: SpryPairLink, account: String?): SpryPairing {
        val key = Entropy.bytes(32)
        val answer = request(link.endpoint, "/v1/pairings/claim", buildJsonObject { put("code", link.code) }, key)
        require(answer["version"]?.jsonPrimitive?.int == 1 && answer["endpoint"]?.jsonPrimitive?.content == link.endpoint)
        val id = answer.getValue("id").jsonPrimitive.content
        require(Regex("^[A-Za-z0-9_-]{43}$").matches(id))
        return SpryPairing(link.endpoint, id, key.toHex(), account)
    }
    suspend fun grant(pair: SpryPairing, scope: String): SpryOffer {
        val answer = request(pair.endpoint, "/v1/grants", buildJsonObject { put("pairing", pair.id); put("scope", Digests.hmacSha256(pair.secretKey.hexToBytes(), "spry-scope-v1:$scope".toByteArray(Charsets.UTF_8)).toHex()) }, pair.secretKey.hexToBytes())
        return requireNotNull(SpryOffer.parse(answer, System.currentTimeMillis() / 1000)).also { require(it.endpoint == pair.endpoint) }
    }
    suspend fun credentials(offer: SpryOffer, key: ByteArray, own: SpryPairing? = null): SpryCallCredential {
        val privateNode = offer.endpoint.startsWith("spry:")
        require(!privateNode || own?.endpoint == offer.endpoint) { "Pair your own Spry node to use private call access." }
        val now = System.currentTimeMillis() / 1000
        require(offer.expiresAt > now && offer.expiresAt <= now + SPRY_GRANT_SECONDS + 60)
        val answer = request(offer.endpoint, "/v1/credentials", buildJsonObject { put("grant", offer.token) }, key)
        val host = URI(offer.endpoint).host
        val allowed = setOf("turn:$host:3478?transport=udp", "turn:$host:3478?transport=tcp", "turns:$host:5349?transport=tcp")
        val urls = answer.getValue("urls").jsonArray.map { it.jsonPrimitive.content }
        val username = answer.getValue("username").jsonPrimitive.content
        val credential = answer.getValue("credential").jsonPrimitive.content
        val expires = answer.getValue("expiresAt").jsonPrimitive.long
        val accessExpires = answer.getValue("accessExpiresAt").jsonPrimitive.long
        require(urls.isNotEmpty() && urls.size <= 3 && urls.all { privateNode || it in allowed } && expires > now && expires <= now + 360 &&
            Regex("^[0-9]{1,12}:[a-f0-9]{24}$").matches(username) && username.substringBefore(':').toLong() == expires && Regex("^[A-Za-z0-9+/]{27}=$").matches(credential) &&
            accessExpires >= expires && accessExpires <= now + SPRY_GRANT_SECONDS + 60)
        val node = SpryLink.card(answer["node"], now)
        require(!privateNode || node?.pubkey == SpryControlEndpoint.parse(offer.endpoint).publicKey)
        return SpryCallCredential(requireNotNull(CallRelayConfig.parse(urls.joinToString(","), username, credential)), expires, accessExpires, node)
    }
    suspend fun peers(pair: SpryPairing, room: String, device: String, peers: List<NostrEvent>, revision: Long) {
        if (!pair.endpoint.startsWith("spry:")) return
        request(pair.endpoint, "/v1/peers", buildJsonObject {
            put("pairing", pair.id); put("revision", revision); put("scope", Digests.hmacSha256(pair.secretKey.hexToBytes(), "spry-scope-v1:$room".toByteArray(Charsets.UTF_8)).toHex())
            put("device", Digests.sha256(device.toByteArray(Charsets.UTF_8)).toHex()); put("peers", JsonArray(peers.map { it.toJson() }))
        }, pair.secretKey.hexToBytes())
    }
    suspend fun revoke(pair: SpryPairing) { request(pair.endpoint, "/v1/pairings/remove-self", buildJsonObject { put("id", pair.id) }, pair.secretKey.hexToBytes()) }
}
