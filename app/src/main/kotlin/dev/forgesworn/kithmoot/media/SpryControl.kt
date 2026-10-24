package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.SpryControlEndpoint
import dev.forgesworn.kithmoot.protocol.SpryLink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Own-pairing-only control sockets. Ordinary room adverts never invoke this. */
internal object SpryControl {
    suspend fun request(client: OkHttpClient, endpoint: String, path: String, body: JsonObject, key: ByteArray): JsonObject = withTimeout(11000) {
        val target = SpryControlEndpoint.parse(endpoint)
        val now = System.currentTimeMillis() / 1000
        val request = SpryLink.request(target, path, body, key, now)
        suspendCancellableCoroutine { waiting ->
            val done = AtomicBoolean(false)
            val sockets = Collections.synchronizedList(mutableListOf<WebSocket>())
            fun close() { synchronized(sockets) { sockets.forEach(WebSocket::cancel) } }
            waiting.invokeOnCancellation { done.set(true); close() }
            for (relay in target.relays) {
                if (done.get()) break
                val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                    private var count = 0
                    private var challenges = 0
                    private var publications = 0
                    private var auth: String? = null
                    private fun subscribe(socket: WebSocket) {
                        socket.send(buildJsonArray { add("REQ"); add("spry-reply"); add(buildJsonObject {
                            put("kinds", buildJsonArray { add(24182) }); put("#p", buildJsonArray { add(request.pubkey) });
                            put("#e", buildJsonArray { add(request.id) }); put("since", now - 60); put("limit", 8)
                        }) }.toString())
                    }
                    override fun onOpen(webSocket: WebSocket, response: Response) { subscribe(webSocket) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (done.get()) return
                        if (text.length > 25088 || ++count > 64) { webSocket.cancel(); return }
                        runCatching {
                            val value = Json.parseToJsonElement(text).jsonArray
                            when (value[0].jsonPrimitive.content) {
                                "AUTH" -> {
                                    val challenge = value[1].jsonPrimitive.content
                                    require(challenge.length <= 256 && ++challenges <= 4)
                                    val proof = Events.sign(key, 22242, System.currentTimeMillis() / 1000,
                                        listOf(listOf("relay", relay), listOf("challenge", challenge)), "")
                                    auth = proof.id
                                    webSocket.send(buildJsonArray { add("AUTH"); add(proof.toJson()) }.toString())
                                }
                                "OK" -> if (auth != null && value[1].jsonPrimitive.content == auth && value[2].jsonPrimitive.boolean) {
                                    auth = null; subscribe(webSocket)
                                }
                                "EOSE" -> if (value[1].jsonPrimitive.content == "spry-reply" && auth == null && ++publications <= 4) {
                                    webSocket.send(buildJsonArray { add("EVENT"); add(request.toJson()) }.toString())
                                }
                                "EVENT" -> if (value[1].jsonPrimitive.content == "spry-reply") {
                                    val answer = SpryLink.response(target, request, NostrEvent.fromJson(value[2]), key, System.currentTimeMillis() / 1000)
                                    if (answer != null && done.compareAndSet(false, true)) { close(); waiting.resume(answer) }
                                }
                            }
                        }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { webSocket.cancel() }
                })
                sockets.add(socket)
                if (done.get()) socket.cancel()
            }
        }
    }
}
