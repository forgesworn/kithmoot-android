package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import java.math.BigInteger

data class EncodedNodeAsk(val event: NostrEvent, val requestId: String, val expiresAt: Long)
data class NodeJoinRequest(val id: String, val device: String, val participant: String, val credential: JsonObject, val name: String?, val expiresAt: Long) {
    override fun toString() = "NodeJoinRequest(redacted)"
}
sealed interface NodeJoinAnswer
data object NodeJoinDenied : NodeJoinAnswer
data class NodeRoomAdmission(val secret: ByteArray, val epochHint: Int, val grant: JsonObject, val node: NodeRoomRecord) : NodeJoinAnswer {
    val persistent = true
    val destruct get() = node.existing?.destruct ?: true
    val epoch = 0
    val relays get() = listOf(node.relay)
    val endsAt get() = node.endsAt
    override fun toString() = "NodeRoomAdmission(redacted)"
}
data class NodeAdmissionGrant(val roomSecret: ByteArray, val epochHint: Int, val grant: JsonObject) {
    override fun toString() = "NodeAdmissionGrant(redacted)"
}

/** Purpose-specific NIP-59 with fresh, bounded outer timestamps. A node link
 * permits only this ask; the keeper alone can return a valid grant and key. */
object NodeAdmissions {
    private fun encrypt(text: String, sender: ByteArray, recipient: String, nonce: ByteArray): String {
        val key = Nip44.conversationKey(sender, recipient.hexToBytes())
        return try { Nip44.encrypt(text, key, nonce) } finally { key.fill(0) }
    }
    private fun decrypt(text: String, recipient: ByteArray, sender: String): String {
        val key = Nip44.conversationKey(recipient, sender.hexToBytes())
        return try { Nip44.decrypt(text, key) } finally { key.fill(0) }
    }
    private fun gift(kind: Int, body: JsonObject, sender: ByteArray, recipient: String, now: Long, expiration: Long, random: (Int) -> ByteArray): Pair<NostrEvent, String> {
        val pubkey = Schnorr.publicKeyHex(sender); val content = body.toString()
        val id = Events.eventId(pubkey, now, kind, emptyList(), content)
        val rumor = buildJsonObject { put("kind", kind); put("created_at", now); put("tags", JsonArray(emptyList())); put("content", content); put("pubkey", pubkey); put("id", id) }
        val seal = Events.sign(sender, 13, now, emptyList(), encrypt(rumor.toString(), sender, recipient, random(32)), random(32))
        // Fold's noble Schnorr key generator reduces a 48-byte seed into [1,n-1].
        // Keep this distinct from the 32-byte NIP-44 nonces and signing auxiliaries.
        val seed = random(48).also { require(it.size == 48) }
        val outer = try {
            val order = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
            val scalar = BigInteger(1, seed).mod(order.subtract(BigInteger.ONE)).add(BigInteger.ONE).toByteArray()
            ByteArray(32).also { scalar.copyInto(it, 32 - minOf(32, scalar.size), maxOf(0, scalar.size - 32)) }
                .also { scalar.fill(0) }
        } finally { seed.fill(0) }
        try {
            return Events.sign(outer, 1059, now, listOf(listOf("p", recipient), listOf("expiration", expiration.toString())),
                encrypt(seal.toCompactJson(), outer, recipient, random(32)), random(32)) to id
        } finally { outer.fill(0) }
    }
    private data class Unwrapped(val rumor: JsonObject, val expiration: Long)
    private fun unwrap(raw: JsonElement?, recipient: ByteArray, now: Long): Unwrapped {
        val outer = NodeWire.event(raw)
        require(outer.kind == 1059 && outer.tags.size == 2 && NodeWire.tag(outer, "p") == Schnorr.publicKeyHex(recipient)
            && outer.createdAt <= now + 30 && outer.createdAt >= now - NODE_ASK_SECONDS)
        val expiration = NodeWire.tag(outer, "expiration").toLong()
        require(NodeWire.integer(expiration) && expiration.toString() == NodeWire.tag(outer, "expiration") && expiration > now && expiration <= outer.createdAt + NODE_ASK_SECONDS)
        val seal = NodeWire.event(Json.parseToJsonElement(decrypt(outer.content, recipient, outer.pubkey)), 49152)
        require(seal.kind == 13 && seal.tags.isEmpty() && seal.createdAt == outer.createdAt)
        val rumor = NodeWire.shape(Json.parseToJsonElement(decrypt(seal.content, recipient, seal.pubkey)), setOf("id", "kind", "created_at", "tags", "content", "pubkey"))
        val tags = rumor["tags"] as? JsonArray ?: error("Invalid node rumor")
        require(NodeWire.string(rumor["pubkey"]) == seal.pubkey && NodeWire.number(rumor["created_at"]) == outer.createdAt && tags.isEmpty())
        val kind = NodeWire.number(rumor["kind"]).also { require(it <= Int.MAX_VALUE) }.toInt()
        require(Events.eventId(seal.pubkey, outer.createdAt, kind, emptyList(), NodeWire.string(rumor["content"])) == NodeWire.string(rumor["id"]))
        return Unwrapped(rumor, expiration)
    }
    fun request(node: NodeRoomRecord, deviceSk: ByteArray, credential: JsonObject, now: Long, name: String? = null,
        random: (Int) -> ByteArray = { size -> Entropy.bytes(size) }): EncodedNodeAsk {
        val pinned = NodeRooms.record(node, now); val device = Schnorr.publicKeyHex(deviceSk)
        val proof = NodeWire.event(credential, 8192); val verified = verifyDeviceCredential(proof, pinned.room, now)
        require(verified is CredentialCheck.Valid && verified.device == device && device != pinned.authority && device != pinned.serviceKey)
        val shown = DisplayName.sanitise(name); val expiration = minOf(now + NODE_ASK_SECONDS, pinned.endsAt ?: Long.MAX_VALUE)
        val body = buildJsonObject { put("v", 1); put("profile", NODE_ROOM_LABELS[1]); put("descriptor", pinned.event.id); put("nonce", NodeWire.base64(random(32)))
            put("room", pinned.room); put("credential", credential); if (shown != null) put("name", shown) }
        val (event, id) = gift(20466, body, deviceSk, pinned.authority, now, expiration, random)
        return EncodedNodeAsk(event, id, expiration)
    }
    fun readRequest(node: NodeRoomRecord, authoritySk: ByteArray, raw: JsonElement?, now: Long): NodeJoinRequest? = runCatching {
        val pinned = NodeRooms.record(node, now); require(Schnorr.publicKeyHex(authoritySk) == pinned.authority)
        val unwrapped = unwrap(raw, authoritySk, now); val rumor = unwrapped.rumor
        require(NodeWire.number(rumor["kind"]) == 20466L)
        val body = NodeWire.json(NodeWire.string(rumor["content"]), setOf("v", "profile", "descriptor", "nonce", "room", "credential"), setOf("name"))
        require(NodeWire.number(body["v"]) == 1L && NodeWire.string(body["profile"]) == NODE_ROOM_LABELS[1] && NodeWire.string(body["descriptor"]) == pinned.event.id
            && NodeWire.string(body["room"]) == pinned.room && Regex("^[A-Za-z0-9_-]{43}$").matches(NodeWire.string(body["nonce"])))
        val credential = NodeWire.event(body["credential"], 8192); val verified = verifyDeviceCredential(credential, pinned.room, now)
        val device = NodeWire.string(rumor["pubkey"])
        require(verified is CredentialCheck.Valid && verified.device == device && device != pinned.authority && device != pinned.serviceKey)
        val name = body["name"]?.let { DisplayName.sanitise(NodeWire.string(it)) }
        require(body["name"] == null || NodeWire.string(body["name"]) == name)
        NodeJoinRequest(NodeWire.string(rumor["id"]), device, verified.participant, body.getValue("credential").jsonObject, name, unwrapped.expiration)
    }.getOrNull()
    fun answer(node: NodeRoomRecord, authoritySk: ByteArray, request: JsonElement?, now: Long, admission: NodeAdmissionGrant? = null,
        random: (Int) -> ByteArray = { size -> Entropy.bytes(size) }): NostrEvent {
        val pinned = NodeRooms.record(node, now); val ask = requireNotNull(readRequest(pinned, authoritySk, request, now))
        if (admission != null) {
            val room = deriveRoom(admission.roomSecret)
            try { require(room.roomId == pinned.room && admission.epochHint in 0..MAX_EPOCH && NodeRooms.verifyGrant(admission.grant, pinned, ask.device, now)) }
            finally { room.roomKey.fill(0) }
        }
        val expiration = minOf(ask.expiresAt, now + 90, pinned.endsAt ?: Long.MAX_VALUE)
        val body = buildJsonObject { put("v", 1); put("profile", NODE_ROOM_LABELS[2]); put("descriptor", pinned.event.id); put("room", pinned.room); put("request", ask.id); put("device", ask.device)
            put("outcome", if (admission == null) "deny" else "allow")
            if (admission != null) { put("secret", NodeWire.base64(admission.roomSecret)); put("epoch", admission.epochHint); put("grant", admission.grant) } }
        return gift(20467, body, authoritySk, ask.device, now, expiration, random).first
    }
    fun readAnswer(node: NodeRoomRecord, deviceSk: ByteArray, raw: JsonElement?, requestId: String, requestExpiresAt: Long, now: Long): NodeJoinAnswer? = runCatching {
        val pinned = NodeRooms.record(node, now); val device = Schnorr.publicKeyHex(deviceSk)
        require(NodeWire.key.matches(requestId) && NodeWire.integer(requestExpiresAt) && requestExpiresAt > now)
        val unwrapped = unwrap(raw, deviceSk, now); val rumor = unwrapped.rumor
        require(NodeWire.number(rumor["kind"]) == 20467L && NodeWire.string(rumor["pubkey"]) == pinned.authority
            && unwrapped.expiration <= requestExpiresAt && unwrapped.expiration <= NodeWire.number(rumor["created_at"]) + 90)
        val body = NodeWire.json(NodeWire.string(rumor["content"]), setOf("v", "profile", "descriptor", "room", "request", "device", "outcome"), setOf("secret", "epoch", "grant"))
        require(NodeWire.number(body["v"]) == 1L && NodeWire.string(body["profile"]) == NODE_ROOM_LABELS[2] && NodeWire.string(body["descriptor"]) == pinned.event.id
            && NodeWire.string(body["room"]) == pinned.room && NodeWire.string(body["request"]) == requestId && NodeWire.string(body["device"]) == device)
        if (NodeWire.string(body["outcome"]) == "deny") { require(body.size == 7); return@runCatching NodeJoinDenied }
        val epoch = NodeWire.number(body["epoch"]); require(NodeWire.string(body["outcome"]) == "allow" && epoch <= MAX_EPOCH && NodeRooms.verifyGrant(body["grant"], pinned, device, now))
        val secret = NodeWire.unbase64(NodeWire.string(body["secret"]))
        try {
            require(secret.size == 32)
            val room = deriveRoom(secret)
            try { require(room.roomId == pinned.room) } finally { room.roomKey.fill(0) }
            NodeRoomAdmission(secret, epoch.toInt(), body.getValue("grant").jsonObject, pinned)
        } catch (error: Exception) { secret.fill(0); throw error }
    }.getOrNull()
}
