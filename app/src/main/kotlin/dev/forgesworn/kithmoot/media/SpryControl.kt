package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.lab.MetadataObserver
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.SpryControlEndpoint
import dev.forgesworn.kithmoot.protocol.SpryLink
import dev.forgesworn.kithmoot.relay.RelayGate
import dev.forgesworn.kithmoot.relay.RelayRefusal
import dev.forgesworn.kithmoot.relay.RelayRefusalKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A relay's refusal in a control socket's frame, or null when the frame is
 * not one. The control key answers NIP-42 itself, so `auth-required:` is not
 * a refusal here.
 */
internal fun spryRefusal(frame: JsonArray): RelayRefusalKind? = runCatching {
    when (frame[0].jsonPrimitive.content) {
        "OK" -> if (frame[2].jsonPrimitive.boolean) null
            else RelayRefusal.classify(frame.getOrNull(3)?.jsonPrimitive?.content.orEmpty(), canAuthenticate = true)
        "CLOSED" -> RelayRefusal.classify(frame.getOrNull(2)?.jsonPrimitive?.content.orEmpty(), canAuthenticate = true)
        "NOTICE" -> if (RelayRefusal.noticeAsksToSlowDown(frame.getOrNull(1)?.jsonPrimitive?.content.orEmpty())) RelayRefusalKind.RATE_LIMITED else null
        else -> null
    }
}.getOrNull()

/**
 * Own-pairing-only control sockets. Ordinary room adverts never invoke this.
 *
 * Each request opens a fresh socket to every relay in the pairing. They are
 * public relays, so requests are spaced to one a second per relay, a relay
 * holding this phone off (see [RelayGate]) is skipped, and a relay that
 * refuses is left alone for the rest of the request rather than asked again
 * on its next EOSE.
 */
internal object SpryControl {
    suspend fun request(client: OkHttpClient, endpoint: String, path: String, body: JsonObject, key: ByteArray,
        gate: RelayGate = RelayGate.process): JsonObject = withTimeout(11000) {
        val target = SpryControlEndpoint.parse(endpoint)
        val now = System.currentTimeMillis() / 1000
        val request = SpryLink.request(target, path, body, key, now)
        val relays = target.relays.filter { !gate.isHeld(it, System.currentTimeMillis()) }
        if (relays.isEmpty()) throw SpryRequestException()
        val waiting = CompletableDeferred<JsonObject>()
        val done = AtomicBoolean(false)
        val sockets = Collections.synchronizedList(mutableListOf<WebSocket>())
        fun close() { synchronized(sockets) { sockets.forEach(WebSocket::cancel) } }
        val gaveUp = AtomicInteger(0)
        /** One relay will not answer this request; when none will, the wait ends now rather than at its timeout. */
        fun giveUp(socket: WebSocket) {
            socket.cancel()
            if (gaveUp.incrementAndGet() >= relays.size && done.compareAndSet(false, true)) waiting.completeExceptionally(SpryRequestException())
        }
        try { coroutineScope {
            val dials: List<Job> = relays.map { relay -> launch {
                delay(gate.reserveTurn(relay, System.currentTimeMillis()))
                if (done.get()) return@launch
                val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                    private fun send(socket: WebSocket, text: String) {
                        MetadataObserver.sendAccepted(relay,text) { socket.send(text) }
                    }
                    private var count = 0
                    private var challenges = 0
                    private var publications = 0
                    private var auth: String? = null
                    private var refused = false
                    private fun subscribe(socket: WebSocket) {
                        send(socket, buildJsonArray { add("REQ"); add("spry-reply"); add(buildJsonObject {
                            put("kinds", buildJsonArray { add(24182) }); put("#p", buildJsonArray { add(request.pubkey) });
                            put("#e", buildJsonArray { add(request.id) }); put("since", now - 60); put("limit", 8)
                        }) }.toString())
                    }
                    override fun onOpen(webSocket: WebSocket, response: Response) { MetadataObserver.lifecycle("external-websocket","open",relay); subscribe(webSocket) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        MetadataObserver.frame("external-websocket",relay,"in",text)
                        if (done.get() || refused) return
                        if (text.length > 25088 || ++count > 64) { webSocket.cancel(); return }
                        runCatching {
                            val value = Json.parseToJsonElement(text).jsonArray
                            when (spryRefusal(value)) {
                                RelayRefusalKind.RATE_LIMITED -> { refused = true; gate.rateLimited(relay, System.currentTimeMillis()); giveUp(webSocket); return }
                                RelayRefusalKind.STOP -> { refused = true; giveUp(webSocket); return }
                                null -> Unit
                            }
                            when (value[0].jsonPrimitive.content) {
                                "AUTH" -> {
                                    val challenge = value[1].jsonPrimitive.content
                                    require(challenge.length <= 256 && ++challenges <= 4)
                                    val proof = Events.sign(key, 22242, System.currentTimeMillis() / 1000,
                                        listOf(listOf("relay", relay), listOf("challenge", challenge)), "")
                                    auth = proof.id
                                    send(webSocket, buildJsonArray { add("AUTH"); add(proof.toJson()) }.toString())
                                }
                                "OK" -> if (auth != null && value[1].jsonPrimitive.content == auth && value[2].jsonPrimitive.boolean) {
                                    auth = null; subscribe(webSocket)
                                }
                                "EOSE" -> if (value[1].jsonPrimitive.content == "spry-reply" && auth == null && ++publications <= 4) {
                                    send(webSocket, buildJsonArray { add("EVENT"); add(request.toJson()) }.toString())
                                }
                                "EVENT" -> if (value[1].jsonPrimitive.content == "spry-reply") {
                                    val reply = SpryLink.reply(target, request, NostrEvent.fromJson(value[2]), key, System.currentTimeMillis() / 1000)
                                    if (reply != null && done.compareAndSet(false, true)) {
                                        close()
                                        // A refusal ends the wait with its status, as over HTTPS; its text stays on the node.
                                        val body = reply.body
                                        if (reply.status == 200 && body != null) waiting.complete(body)
                                        else waiting.completeExceptionally(SpryRequestException(reply.status))
                                    }
                                }
                            }
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { MetadataObserver.binaryFrame("external-websocket",relay,"in",bytes) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { MetadataObserver.lifecycle("external-websocket","closed",relay) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        MetadataObserver.lifecycle("external-websocket","failed",relay)
                        // A refused upgrade (429, 503, 403) holds the relay for every pool on the phone.
                        response?.code?.takeIf { it != 101 }?.let { gate.upgradeRefused(relay, it, System.currentTimeMillis()) }
                        if (!refused) { refused = true; giveUp(webSocket) }
                    }
                })
                sockets.add(socket)
                if (done.get()) socket.cancel()
            } }
            try { waiting.await() } finally { done.set(true); dials.forEach(Job::cancel) }
        } } finally { close() }
    }
}
