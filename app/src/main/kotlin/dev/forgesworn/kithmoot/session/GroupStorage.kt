package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.BLOSSOM_AUTH_KIND
import dev.forgesworn.kithmoot.protocol.DELETE_TARGET_TAG
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.GrantReceiver
import dev.forgesworn.kithmoot.protocol.GroupEvent
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.buildGroupToken
import dev.forgesworn.kithmoot.protocol.isHostName
import dev.forgesworn.kithmoot.protocol.storageNodeServer
import dev.forgesworn.kithmoot.protocol.usableGroupBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The client side of group storage (S5): a room that lists its own storage
 * nodes keeps its files on them, and a device that holds a grant from the
 * room's keeper uploads to and reads from them with tokens signed by its room
 * device key. Mirrors `GroupStorage` in the web client's `group-storage.ts`;
 * the contract is `docs/GROUP-GRANTS.md` in wildbloom-node, profile
 * `group-grants/1`, and section numbers below are its.
 *
 * Nothing here ever falls back to a public server or an unauthenticated read,
 * a token never goes in a URL, and every request goes to a base the room
 * lists. Sealing and opening envelopes are the caller's.
 */
const val GROUP_STORAGE_WAITING = "Waiting for the group’s storage."
/** How long the probe, `HEAD`, `POST /groups/v1/events` and a `GET` wait between bytes, seconds. */
const val GROUP_REQUEST_TIMEOUT_SECONDS = 30L
/** A cached read token is reused only while this much of its life is left, seconds. */
const val GROUP_TOKEN_REUSE_MARGIN_SECONDS = 15L
/** How far a device's clock may differ from a node's `Date` before it is blamed for a refused token, seconds. */
const val GROUP_CLOCK_TOLERANCE_SECONDS = 30L
private const val DESCRIPTOR_MAX_CHARS = 64 * 1024
private const val ENVELOPE_MEDIA_TYPE = "application/vnd.forgesworn.encrypted"
private val HEX64 = Regex("^[0-9a-f]{64}$")

/** Why a node left a request unanswered, with what the person is told (contract 11). */
enum class GroupStorageReason(val words: String) {
    NO_GRANT("Group files will be available once the keeper has let this device in."),
    UNREACHABLE(GROUP_STORAGE_WAITING),
    REFUSED(GROUP_STORAGE_WAITING),
    CLOCK("This device's clock is wrong, so the group's storage refused it. Check the date and time."),
    LOCKED_OUT("This device is no longer let in to the group's storage."),
    NODE_OPEN("One of the group's computers is giving files to anyone who asks, so this app has stopped using it. Tell the person who runs it."),
    ABSENT("This file is on the group's computers, and none of them can be reached right now."),
    ;

    /** Not waiting for anything that will change by itself: say so once, and do not retry. */
    val final: Boolean get() = this == CLOCK || this == LOCKED_OUT || this == NODE_OPEN
}

sealed interface GroupUploadResult {
    /** `url` is always `<base>/<sha256>`, built here from the base uploaded to; the node's descriptor is only checked. */
    data class Stored(val url: String, val base: String, val server: String, val size: Long) : GroupUploadResult
    data class Held(val reason: GroupStorageReason) : GroupUploadResult
}

/** Thrown by [GroupStorage.get] when no listed node could give the bytes. */
class GroupStorageException(val reason: GroupStorageReason, message: String = reason.words) : IOException(message)

enum class HeadOutcome { PRESENT, ABSENT, UNCONFIRMED, FAILED }

/** Follows no redirect: a token must never follow one, and a probe that meets one has found an open node. */
internal val groupStorageHttp: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

internal fun groupAuthHeader(event: NostrEvent): String =
    "Nostr " + Base64.getUrlEncoder().withoutPadding().encodeToString(event.toJson().toString().toByteArray(Charsets.UTF_8))

/** One call, cancelled with the coroutine that waits for it. A response that arrives after cancellation is closed. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { response.close() } }
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

private sealed interface Bounded {
    class Bytes(val bytes: ByteArray) : Bounded
    /** The body was written to a file, [size] bytes of it. */
    class Saved(val size: Long) : Bounded
    data object Over : Bounded
    data object Broken : Bounded
}

/** Read a body up to [limit] bytes, stopping the moment a node sends more. */
private suspend fun readBounded(response: Response, limit: Long): Bounded = runInterruptible(Dispatchers.IO) {
    val body = response.body ?: return@runInterruptible Bounded.Bytes(ByteArray(0))
    try {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        body.byteStream().use { input ->
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) return@runInterruptible Bounded.Over
                out.write(buffer, 0, n)
            }
        }
        Bounded.Bytes(out.toByteArray())
    } catch (_: IOException) { Bounded.Broken }
}

/** Write a body to [destination] up to [limit] bytes, stopping the moment a node sends more. A partial file is the caller's to remove. */
private suspend fun saveBounded(response: Response, limit: Long, destination: java.io.File): Bounded = runInterruptible(Dispatchers.IO) {
    val body = response.body ?: return@runInterruptible Bounded.Saved(0)
    try {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        try {
            body.byteStream().use { input -> destination.outputStream().use { output ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) return@runInterruptible Bounded.Over
                    output.write(buffer, 0, n)
                }
            } }
        } finally { buffer.fill(0) }
        Bounded.Saved(total)
    } catch (_: IOException) { Bounded.Broken }
}

/**
 * A device's use of its room's group storage nodes. One instance per open
 * room: it remembers which nodes it has brought up to date this visit and
 * which it found open. Calls run on any dispatcher; each request is cancelled
 * with the coroutine that made it. [close] wipes the device key.
 */
class GroupStorage(
    private val nodes: () -> List<String>,
    deviceSecretKey: ByteArray,
    /** The device's grants. Every use is synchronised on it, as the room's control reader offers it events. */
    private val receiver: GrantReceiver,
    client: OkHttpClient = groupStorageHttp,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Which bases this app can reach: https, not onion. */
    private val usable: (String) -> Boolean = ::usableGroupBase,
    private val onAcknowledged: () -> Unit = {},
    /** Told once per visit per node that serves an unauthenticated read. */
    private val onNodeOpen: (String) -> Unit = {},
    private val onClockWrong: (String, Long) -> Unit = { _, _ -> },
    /** No node has a working grant for this device: the caller asks the keeper (and rate-limits it). */
    private val onNoGrant: () -> Unit = {},
    /** The device's grants have ended everywhere (a revocation or a reset). */
    private val onLockedOut: () -> Unit = {},
) {
    private val deviceKey = deviceSecretKey.copyOf()
    private val timed = client.newBuilder()
        .connectTimeout(GROUP_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).readTimeout(GROUP_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(GROUP_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    // An upload is never capped in total (A3): only a stall ends it, or the caller.
    private val uploads = client.newBuilder()
        .connectTimeout(GROUP_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val ready = HashSet<String>()
    private val open = HashSet<String>()
    private val readying = HashMap<String, Mutex>()
    private val tokens = LinkedHashMap<String, Pair<String, Long>>()
    @Volatile private var clockWrong = false
    @Volatile private var closed = false

    fun close() { closed = true; deviceKey.fill(0) }

    private data class Node(val base: String, val server: String)

    private fun listed(): List<Node> {
        val out = ArrayList<Node>()
        for (base in nodes()) {
            val server = storageNodeServer(base) ?: continue
            if (!isHostName(server) || !usable(base) || out.any { it.base == base }) continue
            out += Node(base, server)
        }
        return out
    }

    private fun hasScope(server: String, scope: String): Boolean =
        synchronized(receiver) { receiver.grantsFor(server, now()).any { scope in it.scopes } }

    private fun isOpen(base: String) = synchronized(lock) { base in open }

    private fun couldAsk(scope: String): Boolean = listed().any { !isOpen(it.base) && !hasScope(it.server, scope) }

    /** Usable, granted that scope, not found open, [prefer] first. `allOpen` when every granted node was found open and none could yet be granted. */
    private fun candidates(scope: String, prefer: String? = null): Pair<List<Node>, Boolean> {
        val listed = listed()
        val ordered = if (prefer == null) listed else listed.filter { it.base == prefer } + listed.filter { it.base != prefer }
        val granted = ordered.filter { hasScope(it.server, scope) }
        val usableNodes = granted.filter { !isOpen(it.base) }
        return usableNodes to (granted.isNotEmpty() && usableNodes.isEmpty() && !couldAsk(scope))
    }

    private fun noGrant(): GroupStorageReason {
        val at = now()
        val listed = listed()
        val active = synchronized(receiver) { listed.any { receiver.grantsFor(it.server, at).isNotEmpty() } }
        if (!active && listed.isNotEmpty() && synchronized(receiver) { listed.all { receiver.lockedOut(it.server, at) } }) {
            onLockedOut(); return GroupStorageReason.LOCKED_OUT
        }
        onNoGrant(); return GroupStorageReason.NO_GRANT
    }

    /** One request: null when the node could not be reached. */
    private suspend fun send(request: Request, upload: Boolean = false): Response? {
        check(!closed) { "This room's storage is closed." }
        return try { (if (upload) uploads else timed).newCall(request).await() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
    }

    private fun reasonOf(response: Response): String = (response.header("x-reason") ?: "").trim().lowercase().take(40)

    /** A signed token, as a header and its expiry. A read token is reused while 15 s of it remain. */
    private fun token(verb: String, node: Node, hash: String, fresh: Boolean = false): Pair<String, Long> {
        val at = now()
        val key = "$verb|${node.base}|$hash"
        if (verb == "get" && !fresh) synchronized(lock) { tokens[key] }?.let { if (it.second - at > GROUP_TOKEN_REUSE_MARGIN_SECONDS) return it }
        check(!closed) { "This room's storage is closed." }
        val event = buildGroupToken(verb, hash, node.server, at, deviceKey)
        val made = groupAuthHeader(event) to event.tagValue("expiration")!!.toLong()
        if (verb == "get") synchronized(lock) {
            tokens[key] = made
            val oldest = tokens.keys.iterator()
            while (tokens.size > 256 && oldest.hasNext()) { oldest.next(); oldest.remove() }
        }
        return made
    }

    private enum class Posted { ACCEPTED, REFUSED, NETWORK }

    /** `POST /groups/v1/events`: 200, 201 and 409 are an answer; anything else leaves the event for the next visit. */
    private suspend fun post(node: Node, event: GroupEvent): Posted {
        val request = Request.Builder().url("${node.base}/groups/v1/events")
            .post(event.toJson().toString().toRequestBody("application/json".toMediaType())).build()
        val response = send(request) ?: return Posted.NETWORK
        response.use {
            if (it.code == 200 || it.code == 201 || it.code == 409) {
                synchronized(receiver) { receiver.acknowledge(node.server, event.id) }
                onAcknowledged()
                return Posted.ACCEPTED
            }
        }
        return Posted.REFUSED
    }

    private enum class Ready { READY, OPEN, UNREACHABLE }

    /** Before a node's first request in a visit (6.8, 6.4): the probe, the revocations and resets this device carries, then its own newest grant. */
    private suspend fun ensureReady(node: Node, hash: String): Ready {
        if (isOpen(node.base)) return Ready.OPEN
        if (synchronized(lock) { node.base in ready }) return Ready.READY
        val mutex = synchronized(lock) { readying.getOrPut(node.base) { Mutex() } }
        return mutex.withLock {
            if (isOpen(node.base)) return@withLock Ready.OPEN
            if (synchronized(lock) { node.base in ready }) return@withLock Ready.READY
            bringUp(node, hash)
        }
    }

    private suspend fun bringUp(node: Node, hash: String): Ready {
        when (probe(node, hash)) {
            Probe.OPEN -> { synchronized(lock) { open += node.base }; onNodeOpen(node.server); return Ready.OPEN }
            Probe.UNKNOWN -> return Ready.UNREACHABLE
            Probe.CLOSED -> Unit
        }
        val at = now()
        for (p in synchronized(receiver) { receiver.carry(node.server, at) }) if (post(node, p.event) == Posted.NETWORK) return Ready.UNREACHABLE
        val own = synchronized(receiver) { receiver.ownToPost(node.server, at) }
        if (own != null && post(node, own) == Posted.NETWORK) return Ready.UNREACHABLE
        synchronized(lock) { ready += node.base }
        return Ready.READY
    }

    private enum class Probe { CLOSED, OPEN, UNKNOWN }

    /** `HEAD <base>/<hash>` with no token. Group mode answers 401 whether or not the blob exists; a redirect is a node that answers anyone. */
    private suspend fun probe(node: Node, hash: String): Probe {
        val response = send(Request.Builder().url("${node.base}/$hash").head().build()) ?: return Probe.UNKNOWN
        response.use {
            return when {
                it.code in 300..399 -> Probe.OPEN
                it.code == 401 -> Probe.CLOSED
                it.code == 429 || it.code >= 500 -> Probe.UNKNOWN
                else -> Probe.OPEN
            }
        }
    }

    /** An authenticated request, with the 403 `not-granted` rule of 6.4: post this device's newest grant once, then repeat once. */
    private suspend fun authed(verb: String, node: Node, hash: String, build: (String) -> Request, upload: Boolean = false): Response? {
        var token = token(verb, node, hash)
        var response = send(build(token.first), upload) ?: return null
        if (response.code == 403 && reasonOf(response) == "not-granted") {
            response.close()
            val grant = synchronized(receiver) { receiver.grantsFor(node.server, now()).maxByOrNull { it.createdAt }?.event }
                ?: return Response.Builder().request(build(token.first)).protocol(okhttp3.Protocol.HTTP_1_1).code(403).message("")
                    .header("x-reason", "not-granted").build()
            if (post(node, grant) == Posted.NETWORK) return null
            if (now() >= token.second - GROUP_TOKEN_REUSE_MARGIN_SECONDS) token = token(verb, node, hash, fresh = true)
            response = send(build(token.first), upload) ?: return null
        }
        return response
    }

    /** A 401 for `future` or `expired` whose node `Date` disagrees with ours by more than 30 s: our clock. */
    private fun clockSkew(response: Response): Long? {
        val why = reasonOf(response)
        if (response.code != 401 || (why != "future" && why != "expired")) return null
        val theirs = response.headers.getDate("date")?.time ?: return null
        val skew = Math.round(theirs / 1000.0 - now())
        return skew.takeIf { kotlin.math.abs(it) > GROUP_CLOCK_TOLERANCE_SECONDS }
    }

    private fun wrongClock(server: String, skew: Long) { clockWrong = true; onClockWrong(server, skew) }

    /**
     * Put an envelope on the first listed node that takes it (6.2): in list
     * order, among nodes this device holds an `upload` grant for and that were
     * not found open. A node that fails moves on to the next. [sha256] is the
     * envelope's hash, as sealing computed it.
     */
    suspend fun upload(envelope: ByteArray, sha256: String): GroupUploadResult =
        upload(envelope.toRequestBody(ENVELOPE_MEDIA_TYPE.toMediaType()), envelope.size.toLong(), sha256)

    /**
     * The same for an envelope held in a file, streamed from disk so a large
     * file is never read into memory. The file is read afresh for each node
     * tried; it must not change meanwhile.
     */
    suspend fun upload(envelope: java.io.File, sha256: String): GroupUploadResult =
        upload(envelope.asRequestBody(ENVELOPE_MEDIA_TYPE.toMediaType()), envelope.length(), sha256)

    private suspend fun upload(body: RequestBody, size: Long, sha256: String): GroupUploadResult {
        val hash = sha256.lowercase()
        require(HEX64.matches(hash)) { "The blob hash must be 64 hex characters." }
        if (clockWrong) return GroupUploadResult.Held(GroupStorageReason.CLOCK)
        val (found, allOpen) = candidates("upload")
        if (found.isEmpty()) return GroupUploadResult.Held(if (allOpen) GroupStorageReason.NODE_OPEN else noGrant())
        var unreachable = 0; var refused = 0; var denied = 0
        for (node in found) {
            when (ensureReady(node, hash)) {
                Ready.OPEN -> continue
                Ready.UNREACHABLE -> { unreachable++; continue }
                Ready.READY -> Unit
            }
            val response = authed("upload", node, hash, { auth ->
                Request.Builder().url("${node.base}/upload").header("Authorization", auth).header("X-SHA-256", hash).put(body).build()
            }, upload = true)
            if (response == null) { unreachable++; continue }
            response.use {
                clockSkew(it)?.let { skew -> wrongClock(node.server, skew); return GroupUploadResult.Held(GroupStorageReason.CLOCK) }
                when {
                    it.code == 200 || it.code == 201 -> when (val read = readBounded(it, DESCRIPTOR_MAX_CHARS * 4L)) {
                        is Bounded.Bytes -> if (descriptorFits(read.bytes.toString(Charsets.UTF_8), node.base, hash, size)) {
                            return GroupUploadResult.Stored("${node.base}/$hash", node.base, node.server, size)
                        } else refused++
                        else -> unreachable++
                    }
                    it.code == 403 -> denied++
                    it.code == 429 || it.code >= 500 -> unreachable++
                    else -> refused++
                }
            }
        }
        if (denied > 0 && unreachable == 0) { onNoGrant(); return GroupUploadResult.Held(GroupStorageReason.REFUSED) }
        if (unreachable > 0) return GroupUploadResult.Held(GroupStorageReason.UNREACHABLE)
        if (refused > 0) return GroupUploadResult.Held(GroupStorageReason.REFUSED)
        if (couldAsk("upload")) return GroupUploadResult.Held(noGrant())
        return GroupUploadResult.Held(GroupStorageReason.NODE_OPEN)
    }

    /**
     * A whole envelope by hash, from [prefer] first if listed, then in list
     * order. A node that sends more than [maxBytes] is cut off. The caller
     * checks the hash and opens it.
     */
    suspend fun get(sha256: String, prefer: String? = null, maxBytes: Long): ByteArray =
        (fetch(sha256, prefer, maxBytes) { readBounded(it, maxBytes) } as Bounded.Bytes).bytes

    /**
     * The same, streamed into [destination] (replaced if it exists) so a large
     * file never sits in memory. Returns the byte count. Nothing is checked
     * beyond [maxBytes]; the caller checks the hash and opens it. A partly
     * written file is the caller's to remove.
     */
    suspend fun getToFile(sha256: String, prefer: String? = null, maxBytes: Long, destination: java.io.File): Long =
        (fetch(sha256, prefer, maxBytes) { saveBounded(it, maxBytes, destination) } as Bounded.Saved).size

    private suspend fun fetch(sha256: String, prefer: String?, maxBytes: Long, consume: suspend (Response) -> Bounded): Bounded {
        val hash = sha256.lowercase()
        require(HEX64.matches(hash)) { "The blob hash must be 64 hex characters." }
        if (clockWrong) throw GroupStorageException(GroupStorageReason.CLOCK)
        val (found, allOpen) = candidates("get", prefer)
        if (found.isEmpty()) throw GroupStorageException(if (allOpen) GroupStorageReason.NODE_OPEN else noGrant())
        var reached = 0; var refused = 0; var wrong = 0; var opened = 0
        for (node in found) {
            when (ensureReady(node, hash)) {
                Ready.OPEN -> { opened++; continue }
                Ready.UNREACHABLE -> continue
                Ready.READY -> Unit
            }
            val response = authed("get", node, hash, { auth ->
                Request.Builder().url("${node.base}/$hash").header("Authorization", auth).get().build()
            }) ?: continue
            response.use {
                clockSkew(it)?.let { skew -> wrongClock(node.server, skew); throw GroupStorageException(GroupStorageReason.CLOCK) }
                reached++
                if (it.code == 200 || it.code == 206) {
                    val declared = it.header("content-length")?.toLongOrNull()
                    if (declared != null && declared > maxBytes) { wrong++; return@use }
                    when (val read = consume(it)) {
                        is Bounded.Bytes, is Bounded.Saved -> return read
                        Bounded.Over -> wrong++
                        Bounded.Broken -> Unit
                    }
                } else if (it.code == 403) refused++
            }
        }
        if (refused > 0 && refused == reached) {
            val why = noGrant()
            throw GroupStorageException(if (why == GroupStorageReason.LOCKED_OUT) why else GroupStorageReason.REFUSED)
        }
        if (wrong > 0) throw GroupStorageException(GroupStorageReason.REFUSED, "The file is bigger than this app will take.")
        if (opened == found.size) throw GroupStorageException(if (couldAsk("get")) noGrant() else GroupStorageReason.NODE_OPEN)
        throw GroupStorageException(GroupStorageReason.ABSENT)
    }

    /**
     * An authenticated `HEAD` of a blob on a listed base, with a `get` token:
     * present, absent, unconfirmed (this device's `get` grant has ended), or
     * failed (a base not listed, open, or not answering).
     */
    suspend fun head(base: String, sha256: String): HeadOutcome {
        val hash = sha256.lowercase()
        if (!HEX64.matches(hash)) return HeadOutcome.FAILED
        val node = listed().firstOrNull { it.base == base } ?: return HeadOutcome.FAILED
        if (isOpen(base)) return HeadOutcome.FAILED
        if (!hasScope(node.server, "get")) return HeadOutcome.UNCONFIRMED
        if (ensureReady(node, hash) != Ready.READY) return HeadOutcome.FAILED
        val response = authed("get", node, hash, { auth -> Request.Builder().url("$base/$hash").header("Authorization", auth).head().build() })
            ?: return HeadOutcome.FAILED
        response.use {
            return when (it.code) { 200 -> HeadOutcome.PRESENT; 404 -> HeadOutcome.ABSENT; 403 -> HeadOutcome.UNCONFIRMED; else -> HeadOutcome.FAILED }
        }
    }
}

/** The node's descriptor is held to what was sent: the hash, the size, and a URL on this base whose last segment is the hash. */
internal fun descriptorFits(text: String, base: String, hash: String, size: Long): Boolean = runCatching {
    if (text.length > DESCRIPTOR_MAX_CHARS) return false
    val o = Json.parseToJsonElement(text) as? JsonObject ?: return false
    val sha = (o["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    val declared = (o["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return false
    val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    if (sha.lowercase() != hash || declared != size) return false
    val u = URI(url); val root = URI(base)
    fun port(x: URI) = if (x.port != -1) x.port else if (x.scheme.equals("https", true)) 443 else 80
    if (!u.scheme.equals(root.scheme, true) || !u.host.equals(root.host, true) || port(u) != port(root)) return false
    if (u.rawQuery != null || u.rawFragment != null || u.rawUserInfo != null) return false
    val path = u.rawPath ?: return false
    val leaf = path.substringAfterLast('/')
    if (!Regex("^$hash(?:\\.[a-z0-9]{1,10})?$").matches(leaf)) return false
    path.substringBeforeLast('/') == (root.rawPath ?: "").trimEnd('/')
}.getOrDefault(false)

/** What a group delete came to. */
sealed interface GroupBlobDeleteOutcome {
    /** [detail] is set when the node's answer could not be confirmed by a read. */
    data class Deleted(val detail: String? = null) : GroupBlobDeleteOutcome
    data class Kept(val detail: String) : GroupBlobDeleteOutcome
    data class Failed(val detail: String) : GroupBlobDeleteOutcome
}

/**
 * Delete a blob this device put on a group node: `DELETE <url>` with the
 * stored, single-use signed token, then a look to see whether the node still
 * serves it. A `409 spent` means the claim is back: a failure, never a second
 * delete. A removed device keeps its delete permission for a week but can no
 * longer read, so a 403 to the signed look, or a 401 to the plain one, is
 * reported deleted and not confirmed. Mirrors `deleteGroupBlob` in the web
 * client.
 */
suspend fun deleteGroupBlob(url: String, auth: NostrEvent, client: OkHttpClient = groupStorageHttp,
    confirm: (suspend (String, String) -> HeadOutcome)? = null): GroupBlobDeleteOutcome {
    val bad = GroupBlobDeleteOutcome.Failed("File deletion authorisation could not be verified.")
    val target = runCatching { URI(url) }.getOrNull() ?: return bad
    val host = target.host ?: return bad
    val hash = target.rawPath?.split('/')?.lastOrNull { it.isNotEmpty() } ?: return bad
    fun one(name: String): String? = auth.tags.filter { it.firstOrNull() == name }.singleOrNull()?.getOrNull(1)
    val pinned = one(DELETE_TARGET_TAG)
    if (!HEX64.matches(hash) || auth.kind != BLOSSOM_AUTH_KIND || one("t") != "delete" || one("x") != hash || one("server") != host ||
        (pinned != null && pinned != url) || !Events.verify(auth)) return bad
    val http = client.newBuilder().connectTimeout(GROUP_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(GROUP_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun send(request: Request): Response? = try { http.newCall(request).await() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: IOException) { null }
    val del = send(Request.Builder().url(url).header("Authorization", groupAuthHeader(auth)).delete().build())
        ?: return GroupBlobDeleteOutcome.Failed("Could not reach $host.")
    val code = del.use { it.code to (it.header("x-reason") ?: "").trim().lowercase().take(40) }
    if (code.first == 409) return GroupBlobDeleteOutcome.Failed("$host holds the file again.")
    if (code.first != 200 && code.first != 204 && code.first != 404) {
        val reason = code.second.replace(Regex("[^a-z0-9 -]"), "")
        return GroupBlobDeleteOutcome.Failed("$host answered ${code.first}${if (reason.isNotEmpty()) ": $reason" else ""}.")
    }
    val unconfirmed = GroupBlobDeleteOutcome.Deleted("deleted on $host, not confirmed (this device can no longer read there)")
    if (confirm != null) {
        val outcome = try { confirm(url, hash) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { HeadOutcome.FAILED }
        return when (outcome) {
            HeadOutcome.ABSENT -> GroupBlobDeleteOutcome.Deleted()
            HeadOutcome.PRESENT -> GroupBlobDeleteOutcome.Kept("$host still serves the file.")
            HeadOutcome.UNCONFIRMED -> unconfirmed
            HeadOutcome.FAILED -> GroupBlobDeleteOutcome.Failed("Could not confirm file deletion on $host.")
        }
    }
    val check = send(Request.Builder().url(url).head().build()) ?: return GroupBlobDeleteOutcome.Failed("Could not confirm file deletion on $host.")
    val status = check.use { it.code }
    return when {
        status == 401 -> unconfirmed
        status == 404 || status == 410 -> GroupBlobDeleteOutcome.Deleted()
        status in 200..299 -> GroupBlobDeleteOutcome.Kept("$host still serves the file.")
        else -> GroupBlobDeleteOutcome.Failed("$host answered $status when asked whether the file is gone.")
    }
}

/** The sha256 of an envelope, as a group upload names it. */
internal fun envelopeHash(envelope: ByteArray): String = Digests.sha256(envelope).toHex()
