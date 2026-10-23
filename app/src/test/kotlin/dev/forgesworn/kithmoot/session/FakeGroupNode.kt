package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.GroupEventVerdict
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.ParsedGrant
import dev.forgesworn.kithmoot.protocol.ParsedReset
import dev.forgesworn.kithmoot.protocol.parseGroupEvent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.Base64

/**
 * Group nodes as this app meets them, answered inside the HTTP client: the
 * rules of `wildbloomd` group mode a client can observe, as the web client's
 * `test/group-node.ts` has them. A read without a token is 401 whether or not
 * the blob exists; `POST /groups/v1/events` takes the keeper's events; `PUT
 * /upload`, `GET`, `HEAD` and `DELETE` need one kind 24242 token each from a
 * device with an active grant for that verb. A host with no node is a network
 * failure. Every request is recorded.
 */
class FakeGroupNodes(private val clock: () -> Long) : Interceptor {
    data class Hit(val host: String, val method: String, val path: String, val token: Boolean, val status: Int, val reason: String?)

    inner class Node(val host: String, var authority: String) {
        val blobs = HashMap<String, Pair<ByteArray, MutableSet<String>>>()
        val grants = HashMap<String, ParsedGrant>()
        val revoked = HashSet<String>()
        var floor = 0L
        val spent = HashSet<String>()
        /** Serve any read, as a node not in group mode does. */
        var open = false
        /** Answer every request 503. */
        var down = false
        /** This node's clock is this far ahead, and its `Date` says so. */
        var skew = 0L
        /** Answer a probe with a redirect. */
        var redirect = false
        /** Answer an upload's descriptor with another hash. */
        var lyingDescriptor = false
        fun forgetGrants() { grants.clear() }
    }

    val nodes = HashMap<String, Node>()
    val hits = ArrayList<Hit>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).followRedirects(false).build()

    fun add(host: String, authority: String) = Node(host, authority).also { nodes[host] = it }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val node = nodes[request.url.host] ?: throw IOException("unknown host ${request.url.host}")
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() } ?: ByteArray(0)
        val auth = request.header("Authorization")
        val now = clock() + node.skew
        val origin = "${request.url.scheme}://${request.url.host}" + if (request.url.port != okhttp3.HttpUrl.defaultPort(request.url.scheme)) ":${request.url.port}" else ""
        val (status, payload, reason) = answer(node, request.method, request.url.encodedPath, auth, request.header("X-SHA-256"), body, now, origin)
        hits += Hit(node.host, request.method, request.url.encodedPath, auth != null, status, reason)
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("")
            .header("date", java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                .format(java.time.Instant.ofEpochSecond(now).atZone(java.time.ZoneOffset.UTC)))
        reason?.let { builder.header("x-reason", it) }
        if (status in 300..399) builder.header("location", "https://elsewhere.example/")
        val bytes = if (request.method == "HEAD") ByteArray(0) else payload ?: ByteArray(0)
        payload?.let { builder.header("content-length", it.size.toString()) }
        return builder.body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
    }

    private data class Answer(val status: Int, val payload: ByteArray?, val reason: String?)

    private fun answer(node: Node, method: String, path: String, auth: String?, sha: String?, body: ByteArray, now: Long, origin: String): Answer {
        if (node.down) return Answer(503, null, "busy")
        if (method == "POST" && path.endsWith("/groups/v1/events")) return takeEvent(node, body, now)
        if (method == "PUT" && path.endsWith("/upload")) return upload(node, auth, sha, body, now, origin + path.removeSuffix("/upload"))
        val hash = Regex("/([0-9a-f]{64})$").find(path)?.groupValues?.get(1) ?: return Answer(404, null, "not-found")
        if ((method == "GET" || method == "HEAD") && auth == null) {
            if (node.redirect) return Answer(302, null, null)
            if (!node.open) return Answer(401, null, "missing")
            return node.blobs[hash]?.let { Answer(200, it.first, null) } ?: Answer(404, null, null)
        }
        val verb = if (method == "DELETE") "delete" else "get"
        val token = token(node, auth, verb, hash, now)
        if (token is String) return Answer(if (token == "not-granted") 403 else 401, null, token)
        token as NostrEvent
        if (method == "DELETE") {
            if (!node.spent.add(token.id)) return Answer(409, null, "spent")
            val held = node.blobs[hash]
            if (held == null || token.pubkey !in held.second) return Answer(404, null, null)
            node.blobs.remove(hash)
            return Answer(204, null, null)
        }
        val held = node.blobs[hash] ?: return Answer(404, null, null)
        return Answer(200, held.first, null)
    }

    private fun takeEvent(node: Node, body: ByteArray, now: Long): Answer {
        val verdict = parseGroupEvent(Json.parseToJsonElement(body.toString(Charsets.UTF_8)), now = now, keepers = listOf(node.authority), servers = listOf(node.host))
        if (verdict is GroupEventVerdict.Refused) return Answer(verdict.status, null, verdict.reason)
        when (val p = (verdict as GroupEventVerdict.Ok).parsed) {
            is ParsedReset -> {
                if (p.createdAt <= node.floor) return Answer(409, null, "stale")
                node.floor = p.createdAt
                node.grants.values.filter { it.createdAt <= p.createdAt }.forEach { node.revoked += it.grantId }
            }
            is ParsedGrant -> {
                if (p.status == "revoked") { node.revoked += p.grantId; return Answer(201, null, null) }
                if (p.createdAt <= node.floor) return Answer(409, null, "stale")
                if (node.grants[p.grantId]?.event?.id == p.event.id) return Answer(200, null, null)
                node.grants[p.grantId] = p
            }
        }
        return Answer(201, null, null)
    }

    private fun token(node: Node, auth: String?, verb: String, hash: String, now: Long): Any {
        if (auth == null || !auth.startsWith("Nostr ")) return "missing"
        val event = runCatching { NostrEvent.fromJson(Json.parseToJsonElement(Base64.getUrlDecoder().decode(auth.substring(6)).toString(Charsets.UTF_8))) }
            .getOrNull() ?: return "malformed"
        if (event.kind != 24242 || !Events.verify(event)) return "signature"
        if (event.tagValue("t") != verb || event.tagValue("x") != hash || event.tagValue("server") != node.host) return "malformed"
        val expiration = event.tagValue("expiration")?.toLongOrNull() ?: return "malformed"
        if (expiration <= now) return "expired"
        if (event.createdAt > now + 5) return "future"
        if (expiration - event.createdAt > (if (verb == "delete") 604_801 else 600)) return "lifetime"
        val held = node.grants.values.any { it.device == event.pubkey && it.grantId !in node.revoked && it.expiration > now && verb in it.scopes }
        val kept = verb == "delete" && node.grants.values.any { it.device == event.pubkey && "delete" in it.scopes && it.expiration + 7 * 86_400 > now }
        return if (held || kept) event else "not-granted"
    }

    private fun upload(node: Node, auth: String?, sha: String?, body: ByteArray, now: Long, base: String): Answer {
        val hash = Digests.sha256(body).toHex()
        if (sha != hash) return Answer(400, null, "hash-mismatch")
        val token = token(node, auth, "upload", hash, now)
        if (token is String) return Answer(if (token == "not-granted") 403 else 401, null, token)
        val held = node.blobs.getOrPut(hash) { body to HashSet() }
        held.second += (token as NostrEvent).pubkey
        val named = if (node.lyingDescriptor) "00".repeat(32) else hash
        val descriptor = """{"url":"$base/$hash","sha256":"$named","size":${body.size},"type":"application/octet-stream","uploaded":$now}"""
        return Answer(201, descriptor.toByteArray(), null)
    }
}
