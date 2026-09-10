package dev.forgesworn.kithmoot.projects

import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import android.content.Context
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SharedProjects
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage

/** Only called after signing out, for this suite's repeated-byte synthetic identities. */
internal fun resetProjectTestVault(context: Context, signer: ParticipantSigner) {
    val alias = "kithmoot.projects." + Digests.sha256(signer.pubkey.toByteArray()).toHex()
    EncryptedRoomStorage(context, alias, SharedProjects.MAX_CACHE_BYTES).reset()
}

/** Disposable loopback relay. No production account or external relay is used. */
internal class ProjectTestRelay : AutoCloseable {
    private val server = MockWebServer()
    val events = CopyOnWriteArrayList<NostrEvent>()
    val writes = CopyOnWriteArrayList<NostrEvent>()
    private val sockets = ConcurrentHashMap<WebSocket, ConcurrentHashMap<String, List<JsonObject>>>()
    @Volatile var acknowledge = true
    val url: String get() = "ws://127.0.0.1:${server.port}/"
    fun publish(event: NostrEvent) {
        require(Events.verify(event))
        if (events.none { it.id == event.id }) events.add(event)
        for ((socket, subs) in sockets) for ((id, filters) in subs) {
            if (filters.any { matches(it, event) }) socket.send(buildJsonArray { add("EVENT"); add(id); add(event.toJson()) }.toString())
        }
    }
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                private val subs = ConcurrentHashMap<String, List<JsonObject>>()
                override fun onMessage(socket: WebSocket, text: String) {
                    sockets[socket] = subs
                    val frame = Json.parseToJsonElement(text).jsonArray
                    when (frame[0].jsonPrimitive.content) {
                        "REQ" -> {
                            val id = frame[1].jsonPrimitive.content
                            val filters = frame.drop(2).map { it.jsonObject }; subs[id] = filters
                            events.filter { event -> filters.any { matches(it, event) } }.forEach { event ->
                                socket.send(buildJsonArray { add("EVENT"); add(id); add(event.toJson()) }.toString())
                            }
                            socket.send(buildJsonArray { add("EOSE"); add(id) }.toString())
                        }
                        "CLOSE" -> subs.remove(frame[1].jsonPrimitive.content)
                        "EVENT" -> {
                            val event = NostrEvent.fromJson(frame[1]); writes.add(event); publish(event)
                            if (acknowledge) socket.send(buildJsonArray { add("OK"); add(event.id); add(true); add("Synthetic stored event") }.toString())
                        }
                    }
                }
                override fun onClosing(socket: WebSocket, code: Int, reason: String) { sockets.remove(socket); socket.close(code, reason) }
                override fun onClosed(socket: WebSocket, code: Int, reason: String) { sockets.remove(socket) }
            })
        }
        server.start()
    }
    override fun close() { sockets.keys.forEach { it.close(1001, "Synthetic relay closing") }; server.close() }
    private fun matches(filter: JsonObject, event: NostrEvent): Boolean =
        (filter["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == event.kind } != false) &&
            (filter["authors"]?.jsonArray?.any { it.jsonPrimitive.content == event.pubkey } != false) &&
            filter.filterKeys { it.startsWith("#") }.all { (key, values) -> event.tags.any { tag ->
                tag.size >= 2 && tag[0] == key.drop(1) && values.jsonArray.any { it.jsonPrimitive.content == tag[1] }
            } }
}
