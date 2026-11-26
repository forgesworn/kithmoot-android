package dev.forgesworn.kithmoot.board

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.ROOM_BOARD_CHANNEL
import dev.forgesworn.kithmoot.session.decodeChatEvent
import dev.forgesworn.kithmoot.session.deriveChatChannel
import dev.forgesworn.kithmoot.session.encodeChatEvent
import dev.forgesworn.kithmoot.session.parseBoardPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Where the board stands, as the page last said: KithMoot's `RoomBoardStatus`. */
data class RoomBoardStatus(val ready: Boolean, val pending: Boolean, val error: String? = null)

/** A card the view handed to the room: its id, title and description, as plain text. */
data class RoomBoardCard(val id: String, val title: String, val description: String)

/**
 * This device's copy of one room's board: records of the board's changes, each a Yjs update the page wrote, kept
 * sealed at rest by the app. Records merge in any order, so there is no head and no single writer; the page
 * removes only records it has read or written. See `storage/RoomBoardVault.kt`.
 */
interface RoomBoardCopyStore {
    /** Every record that could be opened, and how many could not (left exactly as they are). */
    fun read(): Pair<List<Pair<String, ByteArray>>, Int>
    /** Keep one more, durably, and say what it is called. */
    fun add(update: ByteArray): String
    /** Forget these. A name that is not kept is not an error. */
    fun remove(ids: List<String>)
}

/**
 * The app's half of the room board's port (KithMoot `docs/room-board-port.md`).
 *
 * The page runs the board and holds no key. This holds them: it reads the board's stream in the room's current
 * epoch and hands the page each part a member sent, opened and checked exactly as chat is; it seals and signs
 * each part the page asks it to and publishes it; it keeps the page's copy; and it passes the view's buttons to
 * the room. It never reads the board's bytes.
 *
 * Every message from the page is untrusted: the page shows what members write. So this seals nothing but a
 * board part in its one honest shape, in the board's channel, and checks it as a reader would.
 *
 * [receive] takes the page's messages in order; [scope] runs them one at a time (a single-threaded dispatcher),
 * and [send] hands the page an answer, from any thread.
 */
class RoomBoardBridge(
    /** The room's id at epoch 0: what credentials name. */
    private val roomId: String,
    private val self: String,
    private val deviceSecretKey: ByteArray,
    /** This device's credential, renewed if it has run out (`RoomSession.ensureCredential`). Throws when it cannot be. */
    private val credential: suspend () -> NostrEvent,
    private val transport: RoomTransport,
    private val copy: RoomBoardCopyStore,
    private val scope: CoroutineScope,
    private val send: (String) -> Unit,
    epoch: EpochKeys,
    private val policy: RoomPolicy? = null,
    private val proof: () -> KindredProof? = { null },
    /** A conference room's end: every board event carries it as its expiration. */
    private val ends: Long? = null,
    private val offers: Offers = Offers(),
    private val hooks: Hooks = Hooks(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** How long a reading waits for the relays to say they have sent everything they hold. A transport that
     *  never says is taken as having done so then: the page may send a little the stream already had. */
    private val replayTimeoutMs: Long = 15_000,
) {
    /** The buttons the room offers on a card. */
    data class Offers(val invite: Boolean = false, val assign: Boolean = false, val files: Boolean = false)

    class Hooks(
        val discuss: (RoomBoardCard) -> Unit = {},
        val invite: () -> Unit = {},
        val assign: (RoomBoardCard) -> Unit = {},
    )

    private class Sealed(val event: NostrEvent, val gen: Int)
    private class Reading(val gen: Int, val job: Job)

    @Volatile private var epoch: EpochKeys = EpochKeys(epoch.epoch, epoch.id, epoch.key.copyOf())
    private var reading: Reading? = null
    private val sealed = LinkedHashMap<String, Sealed>()
    private val adding = HashMap<String, Array<String?>>()
    private var members: List<Pair<String, String>> = emptyList()
    private var ready = false
    /** Asked to close: the page is finishing with its copy, so only that is still answered. */
    private var closing = false
    private var closed = false
    private val finished = CompletableDeferred<Unit>()
    private val _status = MutableStateFlow<RoomBoardStatus?>(null)
    /** Where the board stands, as the page last said; null until it has said. */
    val status: StateFlow<RoomBoardStatus?> = _status

    /** One message from the page, in the order the page sent them. */
    fun receive(raw: String) {
        if (raw.length > ROOM_BOARD_PORT_MAX_MESSAGE) return
        scope.launch { if (!closed) handle(raw) }
    }

    /** Resolves once the page has finished with its copy after [close], or has gone. */
    suspend fun awaitClosed() = finished.await()

    /** The room has moved to [next]: the page reads that epoch's stream and hands it the board. */
    fun rekey(next: EpochKeys) {
        val copied = EpochKeys(next.epoch, next.id, next.key.copyOf())
        scope.launch {
            if (closing) { copied.key.fill(0); return@launch }
            epoch.key.fill(0)
            epoch = copied
            if (ready) post(buildJsonObject { put("t", "rekey") })
        }
    }

    /** Who is in the room now, as the room knows them. A list too long for one message is cut short. */
    fun members(list: List<Pair<String, String>>) {
        scope.launch {
            members = list
            if (ready && !closing) postMembers()
        }
    }

    /** Stop reading and sending. The page finishes with its copy first, if it is still there: see [awaitClosed]. */
    fun close() {
        scope.launch {
            if (closing || closed) return@launch
            closing = true
            reading?.job?.cancel()
            reading = null
            sealed.clear()
            if (ready) post(buildJsonObject { put("t", "close") }) else finish()
        }
    }

    /** The page has gone (its renderer, or the app took it away): nothing more is answered. */
    fun abandon() { scope.launch { finish() } }

    private fun finish() {
        if (closed) return
        closing = true
        closed = true
        reading?.job?.cancel()
        reading = null
        sealed.clear()
        adding.clear()
        epoch.key.fill(0)
        finished.complete(Unit)
    }

    private suspend fun handle(raw: String) {
        val m = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return
        if (m.int("v") != 1L) return
        val re = m.text("re", 32)
        val type = m.text("t", 32)
        // Closing, the page still writes its copy and says where it stands, then that it is done.
        if (closing && type !in setOf("copy.add", "copy.remove", "status", "closed")) return
        when (type) {
            "closed" -> finish()
            "ready" -> {
                ready = true
                post(buildJsonObject { put("t", "start"); put("self", self); put("invite", offers.invite); put("assign", offers.assign); put("files", offers.files) })
                postMembers()
            }
            "listen" -> m.int("gen")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.let { listen(it.toInt()) }
            "unlisten" -> {
                val gen = m.int("gen") ?: return
                if (reading?.gen?.toLong() == gen) { reading?.job?.cancel(); reading = null }
                // The page asks to publish a part only in the reading it sealed it in.
                sealed.values.removeAll { it.gen.toLong() == gen }
            }
            "seal" -> if (re != null) seal(re, m["part"])
            "publish" -> if (re != null) publish(re, m.text("event", 64))
            "copy.read" -> if (re != null) readCopy(re)
            "copy.add" -> if (re != null) addCopy(re, m)
            "copy.remove" -> if (re != null) {
                val ids = (m["records"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { id -> id.length <= 128 } }
                if (ids == null || ids.size > 256) return refuse(re, "The board asked to forget something it cannot")
                runCatching { copy.remove(ids) }.fold({ reply(re) }, { refuse(re, "This board's copy on this phone could not be changed") })
            }
            "status" -> {
                val readyNow = (m["ready"] as? JsonPrimitive)?.content == "true"
                val pending = (m["pending"] as? JsonPrimitive)?.content == "true"
                _status.value = RoomBoardStatus(readyNow, pending, m.text("error", 500))
            }
            "discuss" -> card(m["card"])?.let(hooks.discuss)
            "invite" -> if (offers.invite) hooks.invite()
            "assign" -> if (offers.assign) card(m["card"])?.let(hooks.assign)
        }
    }

    private fun listen(gen: Int) {
        reading?.job?.cancel()
        val root = epoch
        val stream = deriveChatChannel(root.id, root.key, ROOM_BOARD_CHANNEL)
        val filters = listOf(Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(stream.id))))
        val seen = HashSet<String>()
        var caughtUp = false
        fun caught() { if (!caughtUp && reading?.gen == gen && !closing) { caughtUp = true; post(buildJsonObject { put("t", "caughtUp"); put("gen", gen) }) } }
        val job = scope.launch {
            val fallback = launch { delay(replayTimeoutMs); caught() }
            try {
                // Completion comes in order, after every stored event before it has been handed over.
                transport.subscribeReplayed(filters) { fallback.cancel(); caught() }.collect { event ->
                    if (closed || reading?.gen != gen || !seen.add(event.id) || seen.size > MAX_EVENTS_READ) return@collect
                    val message = decodeChatEvent(event, root.id, root.key, now(), policy, ROOM_BOARD_CHANNEL, credentialRoomId = roomId) ?: return@collect
                    val part = message.board ?: return@collect
                    post(buildJsonObject {
                        put("t", "part"); put("gen", gen); put("event", event.id); put("device", message.device)
                        put("sentAt", message.sentAt); put("part", part.toJson())
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The page keeps what it holds and says it is still reading; the next reading starts afresh.
            }
        }
        reading = Reading(gen, job)
    }

    private suspend fun seal(re: String, raw: JsonElement?) {
        val part = parseBoardPart(raw) ?: return refuse(re, "That is not a board change")
        val gen = reading?.gen ?: return refuse(re, "The board is not reading the room yet")
        val at = now()
        val current = try { credential() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        val check = current?.let { verifyDeviceCredential(it, roomId, at) }
        if (current == null || check !is CredentialCheck.Valid) return refuse(re, "This device's access to the room ran out, so the board change was not sent")
        val root = epoch
        val event = try {
            encodeChatEvent("Board update", self, current, root.id, root.key, deviceSecretKey, at, proof = proof(),
                channel = ROOM_BOARD_CHANNEL, credentialRoomId = roomId, roomEnds = ends, board = part)
        } catch (_: Exception) { return refuse(re, "The board change could not be sealed") }
        // A relay can accept what every reader will refuse. Check as a reader.
        if (decodeChatEvent(event, root.id, root.key, at, policy, ROOM_BOARD_CHANNEL, credentialRoomId = roomId)?.board != part)
            return refuse(re, "Room access changed, so the board change was not sent")
        sealed[event.id] = Sealed(event, gen)
        while (sealed.size > MAX_SEALED) sealed.remove(sealed.keys.first())
        reply(re, mapOf("event" to JsonPrimitive(event.id), "createdAt" to JsonPrimitive(event.createdAt)))
    }

    private suspend fun publish(re: String, id: String?) {
        val held = id?.let { sealed.remove(it) } ?: return refuse(re, "That board change is no longer waiting to be sent")
        val accepted = try { transport.publishConfirmed(held.event) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        if (accepted) reply(re) else refuse(re, "No relay took the board change")
    }

    private fun readCopy(re: String) {
        val (records, unread) = try { copy.read() } catch (_: Exception) { return refuse(re, "This board's copy on this phone could not be read") }
        var broken = unread
        for ((id, update) in records) {
            val count = maxOf(1, (update.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
            if (id.length > 128 || count > MAX_RECORD_CHUNKS) { broken++; continue }
            for (index in 0 until count) {
                val piece = update.copyOfRange(index * CHUNK_BYTES, minOf(update.size, (index + 1) * CHUNK_BYTES))
                post(buildJsonObject { put("t", "copy.record"); put("re", re); put("record", id); put("index", index); put("count", count); put("data", Base64.getEncoder().encodeToString(piece)) })
            }
        }
        reply(re, mapOf("unread" to JsonPrimitive(broken)))
    }

    private fun addCopy(re: String, m: JsonObject) {
        val count = m.int("count")?.takeIf { it in 1..MAX_RECORD_CHUNKS.toLong() }?.toInt() ?: return refuse(re, "The board sent its copy wrongly")
        val index = m.int("index")?.takeIf { it in 0 until count.toLong() }?.toInt() ?: return refuse(re, "The board sent its copy wrongly")
        val data = m.text("data", ROOM_BOARD_PORT_MAX_MESSAGE) ?: return refuse(re, "The board sent its copy wrongly")
        val pieces = adding.getOrPut(re) { arrayOfNulls(count) }
        if (pieces.size != count) { adding.remove(re); return refuse(re, "The board sent its copy wrongly") }
        while (adding.size > 8) adding.remove(adding.keys.first())
        pieces[index] = data
        if (pieces.any { it == null }) return
        adding.remove(re)
        val update = try { pieces.map { Base64.getDecoder().decode(it) }.fold(ByteArray(0)) { all, piece -> all + piece } } catch (_: Exception) { return refuse(re, "The board sent its copy wrongly") }
        runCatching { copy.add(update) }.fold({ reply(re, mapOf("record" to JsonPrimitive(it))) }, { refuse(re, "This board could not be kept on this phone") })
    }

    private fun postMembers() {
        // Cut short to fit one message: a member left out shows by key.
        var list = members.map { (key, name) -> key to name.take(200) }
        while (true) {
            val message = buildJsonObject {
                put("t", "members")
                put("members", buildJsonArray { for ((key, name) in list) add(buildJsonObject { put("pubkey", key); put("name", name) }) })
            }
            if (encoded(message).length <= ROOM_BOARD_PORT_MAX_MESSAGE || list.isEmpty()) return post(message)
            list = list.dropLast(maxOf(1, list.size / 8))
        }
    }

    private fun card(raw: JsonElement?): RoomBoardCard? {
        val card = raw as? JsonObject ?: return null
        val id = card.text("id", 200) ?: return null
        return RoomBoardCard(id, card.text("title", 2_000) ?: "", card.text("description", 20_000) ?: "")
    }

    private fun reply(re: String, body: Map<String, JsonElement> = emptyMap()) =
        post(JsonObject(mapOf("t" to JsonPrimitive("reply"), "re" to JsonPrimitive(re), "ok" to JsonPrimitive(true)) + body))

    private fun refuse(re: String, words: String) =
        post(buildJsonObject { put("t", "reply"); put("re", re); put("ok", false); put("error", words) })

    private fun encoded(message: JsonObject) = JsonObject(mapOf("v" to JsonPrimitive(1)) + message).toString()

    private fun post(message: JsonObject) {
        val raw = encoded(message)
        if (raw.length <= ROOM_BOARD_PORT_MAX_MESSAGE) send(raw)
    }

    private fun JsonObject.text(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= max }

    private fun JsonObject.int(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    companion object {
        /** Bytes of a copy record one message carries (KithMoot `ROOM_BOARD_PORT_CHUNK_BYTES`). */
        const val CHUNK_BYTES: Int = 30_000
        private const val MAX_RECORD_CHUNKS: Int = 4096
        /** Events handed over from one reading of the stream; the page stops at 20,000 and says so. */
        private const val MAX_EVENTS_READ: Int = 20_001
        /** The page has at most one part sealed and not yet published at a time. */
        private const val MAX_SEALED: Int = 4

        /** A record's name: the SHA-256 of its bytes, as lower-case hex. */
        fun recordId(update: ByteArray): String = Digests.sha256(update).toHex()
    }
}
