package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

/** NIP-46 requests and responses travel as this kind, encrypted to the other side. */
const val KIND_NOSTR_CONNECT: Int = 24133

/** A `bunker://` link: the signer's key, the relays it listens on, and the secret that lets us in. */
data class BunkerPointer(val remotePubkey: String, val relays: List<String>, val secret: String?) {
    companion object {
        fun parse(text: String): BunkerPointer? {
            val raw = text.trim()
            if (!raw.startsWith("bunker://", ignoreCase = true) || raw.length > 8192) return null
            val rest = raw.substring("bunker://".length)
            val question = rest.indexOf('?')
            val pubkey = (if (question < 0) rest else rest.substring(0, question)).trim('/').lowercase()
            if (!pubkey.matches(Regex("[0-9a-f]{64}"))) return null
            val relays = ArrayList<String>()
            var secret: String? = null
            if (question >= 0) {
                for (pair in rest.substring(question + 1).split('&')) {
                    if (pair.isEmpty()) continue
                    val eq = pair.indexOf('=')
                    val key = if (eq < 0) pair else pair.substring(0, eq)
                    val value = URLDecoder.decode(if (eq < 0) "" else pair.substring(eq + 1), "UTF-8")
                    when (key) {
                        "relay" -> if (value.startsWith("wss://") || value.startsWith("ws://")) relays.add(value)
                        "secret" -> secret = value.takeIf { it.isNotEmpty() }
                    }
                }
            }
            if (relays.isEmpty()) return null
            return BunkerPointer(pubkey, relays.distinct(), secret)
        }
    }
}

/**
 * The client side of NIP-46, enough for one signer: connect, who are you,
 * sign this, and NIP-44 for the person's own bookmarks.
 *
 * Every request is a kind 24133 event to the signer's key with the JSON
 * request NIP-44 encrypted in `content`; every answer comes back the same way
 * addressed to our client key, matched on the request id. The client key is
 * this phone's, generated once at sign-in and kept with the account so the
 * signer keeps recognising it.
 */
class Nip46Client(
    private val pointer: BunkerPointer,
    private val clientSecretKey: ByteArray,
    private val transport: RoomTransport,
    scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** How long one request may wait. A person approving on another device takes a while. */
    private val timeoutMs: Long = 60_000,
) {
    val clientPubkey: String = Schnorr.publicKeyHex(clientSecretKey)
    private val conversation = Nip44.conversationKey(clientSecretKey, pointer.remotePubkey.hexToBytes())
    private val pending = HashMap<String, CompletableDeferred<Answer>>()
    private val lock = Any()
    private val listener: Job
    /** The subscription is on before the first request goes out, or an answer could arrive to nobody. */
    private val listening = CompletableDeferred<Unit>()

    private class Answer(val result: String?, val error: String?)

    init {
        listener = scope.launch {
            transport.subscribe(listOf(Filter(kinds = listOf(KIND_NOSTR_CONNECT), authors = listOf(pointer.remotePubkey),
                tags = mapOf("#p" to listOf(clientPubkey)), since = now() - 60)))
                .onStart { listening.complete(Unit) }
                .collect { event -> deliver(event) }
        }
    }

    private fun deliver(event: NostrEvent) {
        if (event.pubkey != pointer.remotePubkey || !Events.verify(event)) return
        val body = runCatching { Json.parseToJsonElement(Nip44.decrypt(event.content, conversation)).jsonObject }.getOrNull() ?: return
        val id = body["id"]?.jsonPrimitive?.content ?: return
        val waiting = synchronized(lock) { pending.remove(id) } ?: return
        val error = body["error"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val result = body["result"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content
        waiting.complete(Answer(result, error))
    }

    suspend fun request(method: String, params: List<String>): String {
        withTimeoutOrNull(timeoutMs) { listening.await() } ?: throw SignerException("The relay subscription for the signer never opened.")
        val id = Entropy.bytes(16).toHex()
        val waiting = CompletableDeferred<Answer>()
        synchronized(lock) { pending[id] = waiting }
        val body = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", JsonArray(params.map(::JsonPrimitive)))
        }.toString()
        val event = Events.sign(clientSecretKey, KIND_NOSTR_CONNECT, now(), listOf(listOf("p", pointer.remotePubkey)),
            Nip44.encrypt(body, conversation))
        transport.publish(event)
        val answer = withTimeoutOrNull(timeoutMs) { waiting.await() }
        if (answer == null) {
            synchronized(lock) { pending.remove(id) }
            throw SignerException("The signer did not answer. Check it is running and can reach ${pointer.relays.joinToString()}.")
        }
        if (answer.error != null) throw SignerException("The signer refused: ${answer.error}")
        return answer.result ?: throw SignerException("The signer sent an empty answer.")
    }

    /** Presents the secret from the bunker link. The signer answers `ack` when it accepts this client. */
    suspend fun connect() {
        val params = buildList {
            add(pointer.remotePubkey)
            add(pointer.secret ?: "")
        }
        val result = request("connect", params)
        if (result != "ack" && result != pointer.secret) throw SignerException("The signer did not accept the connection.")
    }

    suspend fun getPublicKey(): String {
        val result = request("get_public_key", emptyList())
        return publicKeyFrom(result) ?: throw SignerException("The signer returned something that is not a public key.")
    }

    suspend fun signEvent(unsignedJson: String): NostrEvent {
        val result = request("sign_event", listOf(unsignedJson))
        return runCatching { NostrEvent.fromJson(Json.parseToJsonElement(result)) }.getOrElse {
            throw SignerException("The signer returned something that is not a signed event.")
        }
    }

    suspend fun nip44Encrypt(peer: String, plaintext: String): String = request("nip44_encrypt", listOf(peer, plaintext))
    suspend fun nip44Decrypt(peer: String, payload: String): String = request("nip44_decrypt", listOf(peer, payload))

    fun close() {
        listener.cancel()
        listening.cancel()
        val waiting = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        for (w in waiting) w.completeExceptionally(SignerException("The signer connection was closed."))
    }
}

/** A person whose key sits behind a NIP-46 signer. */
class BunkerSigner(
    override val pubkey: String,
    private val client: Nip46Client,
    private val onClose: () -> Unit = {},
) : ParticipantSigner {
    override val method: String get() = "bunker"

    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
        val signed = client.signEvent(unsignedEventJson(pubkey, kind, createdAt, tags, content))
        return checkedSignedEvent(signed, pubkey, kind, createdAt, tags, content)
    }

    override suspend fun nip44Encrypt(peer: String, plaintext: String): String = client.nip44Encrypt(peer, plaintext)
    override suspend fun nip44Decrypt(peer: String, payload: String): String = client.nip44Decrypt(peer, payload)

    override fun close() { client.close(); onClose() }
}

/** The `https://mysignet.app/?…` sign-in, the way signet-login builds it, and what comes back. */
object SignetSignIn {
    const val ORIGIN = "https://mysignet.app"
    const val CALLBACK = "kithmoot://signet"
    const val APP_ORIGIN = "https://kithmoot.forgesworn.dev"

    fun url(challengeHex: String, appName: String = "KithMoot", at: Long = System.currentTimeMillis() / 1000): String {
        require(challengeHex.matches(Regex("[0-9a-f]{64}")))
        val params = listOf("auth" to "1", "challenge" to challengeHex, "origin" to APP_ORIGIN, "name" to appName,
            "callback" to CALLBACK, "t" to at.toString())
        return "$ORIGIN/?" + params.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
    }

    sealed interface Callback {
        data object Denied : Callback
        data class Failed(val reason: String) : Callback
        /** Signet vouched for this key. With a bunker link the person can sign; without one they can only be recognised. */
        data class SignedIn(val pubkey: String, val bunkerUri: String?, val displayName: String?) : Callback {
            val bunker: BunkerPointer? get() = bunkerUri?.let(BunkerPointer::parse)
        }
    }

    /** Reads the callback Signet sends the browser back to. Never throws: a bad link is a failed sign-in. */
    fun parse(link: String): Callback? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme != "kithmoot" || uri.host != "signet") return null
        val params = LinkedHashMap<String, String>()
        for (pair in (uri.rawQuery ?: "").split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = URLDecoder.decode(if (eq < 0) pair else pair.substring(0, eq), "UTF-8")
            val value = URLDecoder.decode(if (eq < 0) "" else pair.substring(eq + 1), "UTF-8")
            params[key] = value
        }
        if (params["error"] == "denied") return Callback.Denied
        params["error"]?.let { return Callback.Failed(it.take(200)) }
        val pubkey = params["pubkey"]?.let(::publicKeyFrom) ?: return Callback.Failed("Signet did not say which key signed in.")
        val bunker = params["bunker"]?.takeIf { BunkerPointer.parse(it) != null }
        val name = params["display_name"]?.trim()?.take(128)?.takeIf { it.isNotEmpty() }
        return Callback.SignedIn(pubkey, bunker, name)
    }
}
