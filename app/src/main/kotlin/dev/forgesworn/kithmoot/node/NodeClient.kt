package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.media.PublicOnlyDns
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NodePair(val origin: String, val serviceKey: String, val owner: String, val turnPorts: NodeTurnPorts = NodeTurnPorts.DEFAULT) {
    init { NodeRooms.canonicalOrigin(origin); require(NodeEvidence.key.matches(serviceKey) && NodeEvidence.key.matches(owner) && serviceKey != owner) }
}
internal data class NodePairLink(val origin: String, val code: String, val expiresAt: Long) {
    override fun toString() = "NodePairLink(redacted)"
    companion object {
        fun parse(url: String, now: Long): NodePairLink {
            try {
            val fragment = if (url.startsWith('#')) url.substring(1) else requireNotNull(URI(url).rawFragment)
            require(fragment.startsWith("kithmoot-node=") && fragment.length <= 2048)
            val encoded = fragment.substring(14)
            require(Regex("^[A-Za-z0-9_-]+$").matches(encoded))
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
            val raw = NodeEvidence.utf8(bytes)
            val body = Json.parseToJsonElement(raw).jsonObject
            require(body.keys == setOf("v", "node", "code", "expiresAt") && body.toString() == raw && NodeEvidence.number(body, "v") == 1L)
            val code = NodeEvidence.string(body, "code"); val end = NodeEvidence.number(body, "expiresAt")
            require(Regex("^[A-Za-z0-9_-]{43}$").matches(code) && end > now && end <= now + 360)
            return NodePairLink(NodeRooms.canonicalOrigin(NodeEvidence.string(body, "node")), code, end)
            } catch (_: Exception) {
                throw IllegalArgumentException("This node pairing link is unavailable. Open your node and pair again.")
            }
        }
    }
}
internal data class NodeStatus(val version: String, val ready: Boolean, val paired: Boolean, val rooms: Long, val devices: Long,
    val pending: Long, val turnReady: Boolean, val relayReady: Boolean, val storeBytes: Long?)
internal sealed interface NodeAccess {
    data class Active(val grant: ParsedGrant, val token: String, val expiresAt: Long) : NodeAccess { override fun toString() = "NodeAccess.Active(redacted)" }
    data class Removed(val grant: ParsedGrant) : NodeAccess
    data class Reset(val reset: ParsedReset) : NodeAccess
    data class Closed(val closure: NostrEvent) : NodeAccess
    data object Ended : NodeAccess
}
internal class NodeTurnLogin(val urls: List<String>, val username: String, val credential: String, val expiresAt: Long, val accessExpiresAt: Long, val issuedAt: Long? = null) {
    // An authenticated node can be slightly ahead of the device. Check the
    // issuer's actual lifetime; NodeCallLease separately caps use at 300s.
    internal fun validLifetime(now: Long): Boolean = expiresAt > now && if (issuedAt == null) expiresAt <= now + 300
        else issuedAt <= now + 30 && issuedAt >= now - 300 && expiresAt > issuedAt && expiresAt <= issuedAt + 300

    override fun toString() = "NodeTurnLogin(redacted)"
}
internal data class NodeOperation(val phase: String, val receipt: NodeCloseReceipt? = null)
/** [code] is the node's short refusal reason, such as `device-already-granted`. It is never
 * shown: the message stays the same whatever the node says. */
internal class NodeRequestException(val status: Int = 0, val code: String? = null) : IOException("Your KithMoot node is unavailable or refused this request.") {
    val retryable: Boolean get() = status == 0 || status in listOf(408, 425, 429) || status in 500..599
    /** The node already holds a different active grant for this device, signed by another keeper of the room. */
    val alreadyGranted: Boolean get() = status == 409 && code == DEVICE_ALREADY_GRANTED
    companion object { const val DEVICE_ALREADY_GRANTED = "device-already-granted" }
}

/** The short reason a node gives with a refusal, from a small JSON body.
 * Best effort: anything else reads as none. */
private fun refusalCode(response: Response): String? = try {
    if (response.header("Content-Type")?.matches(Regex("^application/json(?:;.*)?$", RegexOption.IGNORE_CASE)) != true) null
    else requireNotNull(response.body).byteStream().use { input ->
        val buffer = ByteArray(4097); var count = 0
        while (count < buffer.size) { val read = input.read(buffer, count, buffer.size - count); if (read < 0) break; count += read }
        if (count > 4096) null
        else ((Json.parseToJsonElement(NodeEvidence.utf8(buffer.copyOf(count))) as? JsonObject)?.get("error") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { Regex("^[a-z0-9-]{1,64}$").matches(it) }
    }
} catch (_: Exception) { null }

/** Retry only reads/credential issuance. Each attempt calls the signer again;
 * mutation requests such as admission and closure keep their own semantics. */
internal suspend fun <T> retryNodeRead(action: suspend () -> T): T {
    repeat(2) { attempt ->
        try { return action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: NodeRequestException) {
            if (!failure.retryable) throw failure
            delay(500L shl attempt)
        }
    }
    return action()
}

internal object NodeEvidence {
    val key = Regex("^[a-f0-9]{64}$")
    val operation = Regex("^[a-f0-9]{32}$")
    fun string(obj: JsonObject, field: String) = requireNotNull((obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    fun number(obj: JsonObject, field: String) = requireNotNull((obj[field] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull)
        .also { require(it in 0..9_007_199_254_740_991L) }
    fun bool(obj: JsonObject, field: String) = requireNotNull((obj[field] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull)
    fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun pinned(node: NodeRoomRecord, now: Long) = requireNotNull(NodeRooms.read(node.wire, minOf(now, node.endsAt?.minus(1) ?: now), node.authority))
}

/** One exact HTTPS origin. No redirects, cookies, implicit authentication,
 * alternate node, or raw node error text. Permission is checked across signing
 * and on both sides of the network boundary. */
internal class NodeClient(
    private val http: OkHttpClient = defaultHttp,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val allowed: () -> Boolean = { true },
) {
    private fun permitted() { if (!allowed()) throw NodeRequestException(403) }
    private suspend fun exchange(request: Request): JsonObject {
        permitted()
        try {
            val result = withTimeout(6500) { suspendCancellableCoroutine { waiting ->
                val call = http.newCall(request)
                waiting.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (waiting.isActive) waiting.resumeWithException(NodeRequestException()) }
                    override fun onResponse(call: Call, response: Response) {
                        val decoded = runCatching { response.use {
                            if (!it.isSuccessful) throw NodeRequestException(it.code.takeIf { code -> code in 400..599 } ?: -1, refusalCode(it))
                            require(it.request.url == request.url && it.priorResponse == null)
                            require(it.header("Content-Type")?.matches(Regex("^application/json(?:;.*)?$", RegexOption.IGNORE_CASE)) == true)
                            val bytes = requireNotNull(it.body).byteStream().use { input ->
                                val buffer = ByteArray(65537); var count = 0
                                while (count < buffer.size) { val read = input.read(buffer, count, buffer.size - count); if (read < 0) break; count += read }
                                buffer.copyOf(count)
                            }
                            require(bytes.size <= 65536)
                            val text = NodeEvidence.utf8(bytes)
                            Json.parseToJsonElement(text).jsonObject.also { obj -> require(obj.toString() == text) }
                        } }
                        if (waiting.isActive) decoded.fold(waiting::resume) { error -> waiting.resumeWithException(if (error is NodeRequestException) error else NodeRequestException(-1)) }
                    }
                })
            } }
            permitted(); return result
        } catch (_: TimeoutCancellationException) { throw NodeRequestException() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: NodeRequestException) { throw failure }
        catch (_: Exception) { throw NodeRequestException() }
    }
    private suspend fun request(origin: String, path: String, body: JsonObject, signer: ParticipantSigner): JsonObject {
        permitted(); require(path in PATHS && NodeEvidence.key.matches(signer.pubkey))
        val url = NodeRooms.canonicalOrigin(origin) + path
        val text = body.toString(); require(text.toByteArray().size <= 65536)
        val tags = listOf(listOf("u", url), listOf("method", "POST"), listOf("payload", Digests.sha256(text.toByteArray(Charsets.UTF_8)).toHex()),
            listOf("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(Entropy.bytes(32))))
        val event = signer.sign(27235, now(), tags.map { it.toList() }, "")
        permitted()
        require(event.pubkey == signer.pubkey && event.kind == 27235 && event.content.isEmpty() && event.tags == tags
            && event.createdAt in now() - 60..now() + 30 && Events.verify(event))
        return exchange(Request.Builder().url(url).post(text.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Nostr " + Base64.getEncoder().encodeToString(event.toCompactJson().toByteArray(Charsets.UTF_8)))
            .header("Cache-Control", "no-store").build())
    }
    suspend fun pair(link: NodePairLink, owner: ParticipantSigner): NodePair {
        permitted(); require(link.expiresAt > now() && link.expiresAt <= now() + 360 && Regex("^[A-Za-z0-9_-]{43}$").matches(link.code))
        val origin = NodeRooms.canonicalOrigin(link.origin)
        val info = exchange(Request.Builder().url("$origin/node/v1/info").header("Cache-Control", "no-store").build())
        val service = NodeEvidence.string(info, "serviceKey")
        require(NodeEvidence.number(info, "v") == 1L && NodeEvidence.string(info, "profile") == "kithmoot-node/1"
            && NodeEvidence.string(info, "origin") == origin && NodeEvidence.key.matches(service) && service != owner.pubkey
            && NodeEvidence.string(info, "relay") == "wss://${URI(origin).host}/events" && NodeEvidence.string(info, "calls") == "relay-only" && NodeEvidence.string(info, "files") == "off")
        val ports = requireNotNull(NodeRooms.readTurnPorts(info["turnPorts"]))
        require(NodeEvidence.bool(request(origin, "/node/v1/claim", buildJsonObject { put("code", link.code) }, owner), "paired"))
        return NodePair(origin, service, owner.pubkey, ports)
    }
    suspend fun enrol(pair: NodePair, record: NodeRoomRecord, owner: ParticipantSigner) {
        val node = NodeEvidence.pinned(record, now()); require(pair.owner == owner.pubkey && pair.origin == node.origin && pair.serviceKey == node.serviceKey && pair.turnPorts == node.turnPorts)
        require(NodeEvidence.bool(request(node.origin, "/node/v1/enrol", buildJsonObject { put("room", node.room); put("authority", node.authority); put("inviter", node.authority); if (node.existing != null) put("existing", true); node.endsAt?.let { put("ends", it) } }, owner), "enrolled"))
    }
    suspend fun status(pair: NodePair, owner: ParticipantSigner): NodeStatus {
        require(pair.owner == owner.pubkey)
        val body = request(pair.origin, "/node/v1/status", buildJsonObject {}, owner)
        require(NodeEvidence.number(body, "v") == 1L && NodeEvidence.string(body, "serviceKey") == pair.serviceKey)
        val version = NodeEvidence.string(body, "version").also { require(Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$").matches(it)) }
        return NodeStatus(version, NodeEvidence.bool(body, "ready"), NodeEvidence.bool(body, "paired"), NodeEvidence.number(body, "rooms"), NodeEvidence.number(body, "devices"),
            NodeEvidence.number(body, "pending"), NodeEvidence.bool(body, "turnReady"), NodeEvidence.bool(body, "relayReady"), body["storeBytes"]?.let { NodeEvidence.number(body, "storeBytes") })
    }
    suspend fun postGrant(record: NodeRoomRecord, event: GroupEvent, authority: ParticipantSigner) {
        val node = NodeEvidence.pinned(record, now())
        val parsed = (parseGroupEvent(event.toJson(), now(), listOf(node.authority), listOf(URI(node.origin).host)) as? GroupEventVerdict.Ok)?.parsed
        require(parsed != null && parsed.roomId == node.room && authority.pubkey == node.authority)
        if (parsed is ParsedGrant) require(parsed.scopes.all { it in listOf("turn", "relay-read", "relay-write") })
        require(NodeEvidence.bool(request(node.origin, "/groups/v1/events", event.toJson(), authority), "accepted"))
    }
    suspend fun access(record: NodeRoomRecord, grantId: String?, device: ParticipantSigner): NodeAccess {
        val node = NodeEvidence.pinned(record, now()); if (node.endsAt?.let { it <= now() } == true) return NodeAccess.Ended
        require(if(grantId==null)node.existing!=null else NodeEvidence.operation.matches(grantId))
        val body = request(node.origin, "/node/v1/access", buildJsonObject { put("room", node.room); if(grantId!=null)put("grant", grantId) }, device)
        val state = NodeEvidence.string(body, "state")
        if (state == "closed") return NodeAccess.Closed(requireNotNull(NodeRooms.recoverClosure(body["proof"], node, now())))
        val parsed = (parseGroupEvent(body["event"], now(), listOf(node.authority), listOf(URI(node.origin).host)) as? GroupEventVerdict.Ok)?.parsed
        require(parsed != null && parsed.roomId == node.room)
        if (state == "reset" && parsed is ParsedReset) return NodeAccess.Reset(parsed)
        require(parsed is ParsedGrant && parsed.device == device.pubkey && (grantId == null || parsed.grantId == grantId))
        if (state == "removed" && parsed.status == "revoked") return NodeAccess.Removed(parsed)
        require(state == "active" && NodeRooms.verifyGrant(body["event"], node, device.pubkey, now()))
        val offer = body.getValue("offer").jsonObject; val expiration = NodeEvidence.number(offer, "expiresAt"); val token = NodeEvidence.string(offer, "token")
        require(NodeEvidence.number(offer, "v") == 1L && NodeEvidence.string(offer, "endpoint") == node.origin && Regex("^[A-Za-z0-9_-]{43}$").matches(token)
            && expiration > now() && expiration <= now() + NODE_HORIZON_SECONDS + 30)
        return NodeAccess.Active(parsed, token, expiration)
    }
    suspend fun turn(record: NodeRoomRecord, access: NodeAccess.Active, device: ParticipantSigner): NodeTurnLogin {
        val node = NodeEvidence.pinned(record, now())
        require(NodeRooms.verifyGrant(access.grant.event.toJson(), node, device.pubkey, now()) && access.expiresAt > now())
        val body = request(node.origin, "/v1/credentials", buildJsonObject { put("grant", access.token) }, device)
        val urls = body.getValue("urls").jsonArray.map { require(it is JsonPrimitive && it.isString); it.content }
        val username = NodeEvidence.string(body, "username"); val credential = NodeEvidence.string(body, "credential")
        val expiration = NodeEvidence.number(body, "expiresAt"); val accessEnd = NodeEvidence.number(body, "accessExpiresAt")
        val login = NodeTurnLogin(urls, username, credential, expiration, accessEnd, body["issuedAt"]?.let { NodeEvidence.number(body, "issuedAt") })
        require(urls.size in 1..4 && urls.distinct() == urls && urls.all { it in node.turnUrls }
            && Regex("^[0-9]{1,12}:[a-f0-9]{24}$").matches(username) && Regex("^[A-Za-z0-9+/]{27}=$").matches(credential)
            && login.validLifetime(now()) && username.substringBefore(':').toLong() == expiration
            && accessEnd >= expiration && accessEnd <= minOf(access.grant.expiration, node.endsAt ?: Long.MAX_VALUE))
        return login
    }
    suspend fun close(record: NodeRoomRecord, instruction: JsonObject, authority: ParticipantSigner): NodeOperation {
        val node = NodeEvidence.pinned(record, now()); require(authority.pubkey == node.authority && NodeEvidence.string(instruction, "room") == node.room)
        val operation = NodeEvidence.string(instruction, "operation").also { require(NodeEvidence.operation.matches(it)) }
        return operation(request(node.origin, "/node/v1/close", instruction, authority), operation, node.serviceKey)
    }
    suspend fun operation(pair: NodePair, operation: String, owner: ParticipantSigner): NodeOperation {
        require(pair.owner == owner.pubkey && NodeEvidence.operation.matches(operation))
        return operation(request(pair.origin, "/node/v1/operation", buildJsonObject { put("operation", operation) }, owner), operation, pair.serviceKey)
    }
    private fun operation(body: JsonObject, operation: String, serviceKey: String): NodeOperation {
        require(NodeEvidence.string(body, "operation") == operation)
        val phase = NodeEvidence.string(body, "phase").also { require(it in listOf("publishing", "draining", "teardown", "erasing", "done")) }
        return NodeOperation(phase, if (phase == "done") requireNotNull(NodeRooms.receipt(body["result"]?.jsonObject?.get("receipt"), serviceKey, operation, now())) else null)
    }
    companion object {
        private val PATHS = setOf("/node/v1/claim", "/node/v1/enrol", "/groups/v1/events", "/node/v1/access", "/node/v1/close", "/node/v1/operation", "/node/v1/status", "/v1/credentials")
        private val defaultHttp by lazy { OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).dns(PublicOnlyDns()).build() }
    }
}
