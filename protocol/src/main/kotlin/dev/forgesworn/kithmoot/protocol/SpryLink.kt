package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder

/** Pure protocol adapter: no sockets, node platform or media dependencies. */
data class SpryControlEndpoint(val publicKey: String, val relays: List<String>) {
    companion object {
        fun parse(value: String): SpryControlEndpoint {
            require(value.length <= 2048)
            val uri = URI(value)
            val key = requireNotNull(uri.rawAuthority)
            require(uri.scheme == "spry" && Regex("^[a-f0-9]{64}$").matches(key) && uri.rawFragment == null && uri.path in listOf("", "/"))
            val relays = requireNotNull(uri.rawQuery).split('&').map { pair ->
                val parts = pair.split('=', limit = 2)
                require(parts.size == 2 && parts[0] == "relay")
                val relay = URLDecoder.decode(parts[1], "UTF-8")
                require(relay.length <= 256)
                val url = URI(relay)
                val host = requireNotNull(url.host).lowercase()
                // A trailing dot (`localhost.`) would slip a local name past the suffix check.
                require(url.scheme == "wss" && host.contains('.') && !host.endsWith('.') && url.userInfo == null && url.rawQuery == null && url.rawFragment == null &&
                    !Regex("^[0-9.]+$").matches(host) && !host.startsWith('[') &&
                    !listOf("local", "localhost", "internal", "invalid", "test").any { host == it || host.endsWith(".$it") } && (url.port == -1 || url.port in 1..65535))
                "wss://$host" + (if (url.port == -1 || url.port == 443) "" else ":${url.port}") + url.rawPath.ifEmpty { "/" }
            }
            require(relays.size in 1..4 && relays.distinct().size == relays.size)
            return SpryControlEndpoint(key, relays.sorted())
        }
    }
}

/** A verified reply on the control channel: the node's status, and its body when 200. */
data class SpryReply(val status: Int, val body: JsonObject?)

object SpryLink {
    /** Every path a client may ask a Spry node for. */
    val PATHS = setOf("/v1/pairings/claim", "/v1/pairings/remove-self", "/v1/grants", "/v1/grants/remove", "/v1/grants/devices",
        "/v1/grants/vouch", "/v1/credentials", "/v1/peers")
    private val paths = PATHS
    private fun unique(event: NostrEvent, name: String): String? = event.tags.filter { it.firstOrNull() == name }.singleOrNull()?.takeIf { it.size == 2 }?.get(1)
    fun card(value: JsonElement?, now: Long): NostrEvent? = runCatching {
        require(value.toString().length <= 2048)
        val event = NostrEvent.fromJson(requireNotNull(value))
        require(event.kind == 24183 && event.createdAt <= now + 60 && event.content.length <= 256 && event.tags.size <= 8 && Events.verify(event))
        val expiry = requireNotNull(unique(event, "expiration")).toLong()
        require(expiry > now && expiry <= now + 660 && expiry <= event.createdAt + 600)
        val content = Json.parseToJsonElement(event.content).jsonObject
        val address = content.getValue("address").jsonPrimitive.content
        require(content["v"]?.jsonPrimitive?.int == 1 && content["profile"]?.jsonPrimitive?.content == "spry-peer-v1" && Regex("^\\[(fc|fd)[a-fA-F0-9:]+\\]:3745$").matches(address))
        URI("http://$address").parseServerAuthority()
        event
    }.getOrNull()

    /** The only two spellings of a `spry-standard-v1` card's content: Spry
     *  writes the first. Compared as strings, never parsed, so two
     *  implementations cannot disagree about `1.0`, duplicate keys or whitespace. */
    private val STANDARD_CONTENT = setOf("{\"profile\":\"spry-standard-v1\",\"v\":1}", "{\"v\":1,\"profile\":\"spry-standard-v1\"}")
    private val EXPIRY = Regex("^[0-9]{1,15}$")

    /**
     * A standard-profile node card (spry-turn `docs/supervised-mode.md`, "The
     * `spry-standard-v1` node card"), such as a member's built-in relay: kind
     * 24183 signed by the node's control key, exactly one tag, `expiration`, no
     * more than 600 seconds after `created_at` and in the future, `created_at`
     * at most 60 seconds ahead, and content naming the profile and nothing
     * else. It carries no address and is never a peer route: [card] refuses
     * it, and this refuses a peer card. The web's `spryStandardCard`.
     */
    fun standardCard(value: JsonElement?, now: Long): NostrEvent? = runCatching {
        val raw = value as? JsonObject ?: return null
        require(raw.toString().length <= 2048)
        val event = NostrEvent.fromJson(raw)
        require(event.kind == 24183 && event.createdAt <= now + 60 && event.content in STANDARD_CONTENT && event.tags.size == 1)
        val tag = event.tags.single()
        require(tag.size == 2 && tag[0] == "expiration" && EXPIRY.matches(tag[1]))
        val expiry = tag[1].toLong()
        require(expiry > now && expiry <= now + 660 && expiry <= event.createdAt + 600 && Events.verify(event))
        event
    }.getOrNull()

    fun request(target: SpryControlEndpoint, path: String, body: JsonObject, key: ByteArray, now: Long): NostrEvent {
        require(path in paths && body.toString().toByteArray(Charsets.UTF_8).size <= 8192)
        val plaintext = buildJsonObject { put("v", 1); put("path", path); put("body", body) }
        return Events.sign(key, 24181, now, listOf(listOf("p", target.publicKey), listOf("expiration", (now + 30).toString())),
            Nip44.encrypt(plaintext.toString(), Nip44.conversationKey(key, target.publicKey.hexToBytes())))
    }
    fun response(target: SpryControlEndpoint, request: NostrEvent, event: NostrEvent, key: ByteArray, now: Long): JsonObject? =
        reply(target, request, event, key, now)?.takeIf { it.status == 200 }?.body

    /** A pinned, encrypted reply with its status, so a refusal ends the wait at
     *  once and its status reaches the caller; a non-200 body is never kept. */
    fun reply(target: SpryControlEndpoint, request: NostrEvent, event: NostrEvent, key: ByteArray, now: Long): SpryReply? = runCatching {
        require(event.toCompactJson().length <= 24576 && event.pubkey == target.publicKey && event.kind == 24182 && event.createdAt <= now + 60 && event.tags.size <= 8 &&
            unique(event, "p") == Schnorr.publicKeyHex(key) && unique(event, "e") == request.id)
        val expiry = requireNotNull(unique(event, "expiration")).toLong()
        require(expiry > now && expiry <= minOf(now + 90, event.createdAt + 30) && Events.verify(event))
        val raw = Json.parseToJsonElement(Nip44.decrypt(event.content, Nip44.conversationKey(key, target.publicKey.hexToBytes()))).jsonObject
        val body = raw.getValue("body").jsonObject
        val status = raw.getValue("status").jsonPrimitive.int
        require(raw["v"]?.jsonPrimitive?.int == 1 && status in 200..599 && body.toString().toByteArray(Charsets.UTF_8).size <= 8192)
        // Keep issuer failure text and capabilities out of exceptions/logs.
        SpryReply(status, body.takeIf { status == 200 })
    }.getOrNull()
}
