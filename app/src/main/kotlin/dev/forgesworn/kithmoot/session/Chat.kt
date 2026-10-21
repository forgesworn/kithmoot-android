package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.RoomLogoPayload
import dev.forgesworn.kithmoot.protocol.roomLogoPayload
import dev.forgesworn.kithmoot.protocol.evaluateAccess
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import dev.forgesworn.kithmoot.protocol.withRoomExpiration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Room chat.
 *
 * Not part of the published interop vectors, so it lives here rather than in
 * `:protocol`, where everything is pinned byte-for-byte against the reference
 * implementation. It is built the same way as the roster - encrypted to the
 * room key, signed by the device, carrying the device's credential so any member
 * can check who said it without asking a server.
 */
const val KIND_CHAT: Int = 1460
const val MAX_CHAT_TEXT_LENGTH: Int = 2_000
const val CHAT_RETENTION_SECONDS: Long = 30L * 24 * 60 * 60
const val MAX_CHAT_MESSAGES_PER_MINUTE: Int = 30
private const val MAX_CHAT_CLOCK_SKEW_SECONDS: Long = 300

data class ChatChannel(val id: String, val key: ByteArray)

/** A relay knowing only the public room ID cannot derive a private channel. */
fun deriveChatChannel(roomId: String, roomKey: ByteArray, channel: String? = null): ChatChannel {
    if (channel == null) return ChatChannel(roomId, roomKey)
    require(channel.isNotEmpty() && channel.length <= 64) { "channel name out of range" }
    fun derive(info: String) = Digests.hkdfSha256(roomKey, null, info.toByteArray(Charsets.UTF_8), 32)
    return ChatChannel(derive("kithmoot/v1/channel-id/$channel").toHex(), derive("kithmoot/v1/channel-key/$channel"))
}

/** A line of chat, attributed to the person rather than the device that typed it. */
data class ChatMessage(
    val id: String,
    val participant: String,
    val device: String,
    val body: String,
    val sentAt: Long,
    val name: String? = null,
    /**
     * The same moment in milliseconds, for order only: two messages in one
     * second otherwise fall to their ids. Inside the ciphertext alone; kept
     * only within [sentAt]'s second. See [compareMessages].
     */
    val sentAtMs: Long? = null,
    val reaction: ChatReaction? = null,
    /** The message this answers, and the root of its thread. See Messages.kt. */
    val reply: MessageRef? = null,
    val thread: MessageRef? = null,
    /** The id of the ORIGINAL this replaces, same author: an edit. */
    val replaces: String? = null,
    /** The id of the original this retracts, same author: a tombstone. */
    val retracts: String? = null,
    /** Who this addresses: participant keys, and `everyone`. */
    val mentions: List<String>? = null,
    /** A direct-message invitation sealed to one member. */
    val invite: ChatInvite? = null,
    /**
     * The lane this message actually travelled, worked out by the reader from
     * the relays it came over. Never on the wire: the decoder reads named
     * fields only, so a `lane` a payload carries is ignored, because the
     * sender does not get to say how private the delivery was. Null when the
     * transport could not say. See `Lane.kt`.
     */
    val lane: Lane? = null,
    /** Verified participant signature, bound to this room and outer device. */
    val assignment: NostrEvent? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val artwork: List<ChatArtwork> = emptyList(),
    /** Private metadata on control chat; a payload with no image removes the override. */
    val roomLogo: RoomLogoPayload? = null,
)

/** After authenticating an owned message, retain its original authority
 * window before entering a transport guard. Renewed session credentials or
 * admission proofs must not extend an already signed, restored message.
 * Decryption happens here, never inside the cheap dispatch/ACK callback.
 */
internal fun originalChatPublicationDeadline(event: NostrEvent, roomId: String, roomKey: ByteArray): Long {
    val address = deriveChatChannel(roomId, roomKey)
    val json = Json.parseToJsonElement(Nip44.decrypt(event.content, address.key)).jsonObject
    val credential = NostrEvent.fromJson(json.getValue("credential").jsonObject)
    val credentialDeadline = credential.tagValue("expiration")?.toLongOrNull() ?: 0L
    val proofDeadline = json["proof"]?.let { requireNotNull(KindredProof.fromJson(it.jsonObject)).expiresAt } ?: Long.MAX_VALUE
    val roomDeadline = event.tagValue("expiration")?.let { it.toLongOrNull() ?: 0L } ?: Long.MAX_VALUE
    return minOf(credentialDeadline, proofDeadline, roomDeadline)
}

fun encodeChatEvent(
    body: String,
    participant: String,
    credential: NostrEvent,
    roomId: String,
    roomKey: ByteArray,
    deviceSecretKey: ByteArray,
    sentAt: Long,
    proof: KindredProof? = null,
    id: String = Entropy.bytes(16).toHex(),
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
    reaction: ChatReaction? = null,
    reply: MessageRef? = null,
    thread: MessageRef? = null,
    replaces: String? = null,
    retracts: String? = null,
    mentions: List<String>? = null,
    invite: ChatInvite? = null,
    channel: String? = null,
    assignment: NostrEvent? = null,
    credentialRoomId: String = roomId,
    /** A conference room's end; see `withRoomExpiration`. */
    roomEnds: Long? = null,
    /** [sentAt] in milliseconds, within its second; see [ChatMessage.sentAtMs]. */
    sentAtMs: Long? = null,
    attachments: List<ChatAttachment> = emptyList(),
    artwork: List<ChatArtwork> = emptyList(),
    roomLogo: RoomLogoPayload? = null,
): NostrEvent {
    require(sentAtMs == null || Math.floorDiv(sentAtMs, 1000L) == sentAt) { "sentAtMs must fall within sentAt's second" }
    if (roomLogo != null) {
        require(roomLogoPayload(body, roomLogo.image?.toJson() ?: JsonNull, channel) != null) { "Invalid room logo envelope" }
        require(listOfNotNull(reaction, reply, thread, replaces, retracts, mentions, invite, assignment).isEmpty() &&
            attachments.isEmpty() && artwork.isEmpty()) { "A room logo is a single statement" }
    }
    if (assignment != null) {
        val payload = assignmentPayload(assignment, credentialRoomId)
        require(channel == ASSIGNMENT_CHANNEL && payload != null && assignment.pubkey == participant &&
            (payload.device == null || payload.device == Schnorr.publicKeyHex(deviceSecretKey))) { "Invalid assignment envelope" }
        require(listOfNotNull(reaction, reply, thread, replaces, retracts, mentions, invite).isEmpty()) { "An assignment is a single statement" }
    }
    val address = deriveChatChannel(roomId,roomKey,channel)
    require(reaction == null || parseReaction(reaction.toJson()) != null) { "Invalid reaction" }
    require(listOfNotNull(reaction, replaces, retracts, invite).size <= 1) { "a message says one thing about another message, not two" }
    require(replaces == null || validMessageId(replaces)) { "an edit must name the message it replaces" }
    require(retracts == null || validMessageId(retracts)) { "a retraction must name the message it retracts" }
    require(mentions == null || mentions.size <= MAX_MENTIONS) { "a message names at most $MAX_MENTIONS participants" }
    require(attachments.size <= 4 && attachments.all { parseAttachment(it.toJson()) != null })
    require(attachments.isEmpty() || listOfNotNull(reaction, retracts, invite, assignment).isEmpty())
    require(artwork.size <= MAX_CHAT_ARTWORK)
    val normalArtwork = artwork.map { requireNotNull(normaliseArtwork(it)) { "Invalid artwork reference" } }
    require(artwork.isEmpty() || listOfNotNull(reaction, retracts, invite, assignment).isEmpty())
    val plaintext: JsonObject = buildJsonObject {
        put("id", id)
        put("participant", participant)
        put("device", Schnorr.publicKeyHex(deviceSecretKey))
        put("credential", credential.toJson())
        proof?.let { put("proof", it.toJson()) }
        if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map { it.toJson() }))
        if (normalArtwork.isNotEmpty()) put("artwork", JsonArray(normalArtwork.map { it.toJson() }))
        put("text", if (body.isBlank() && normalArtwork.isNotEmpty()) artworkFallback(normalArtwork) else body)
        put("sentAt", sentAt)
        sentAtMs?.let { put("sentAtMs", it) }
        reaction?.let { put("reaction", it.toJson()) }
        reply?.let { put("reply", it.toJson()) }
        thread?.let { put("thread", it.toJson()) }
        replaces?.let { put("replaces", it) }
        retracts?.let { put("retracts", it) }
        mentions?.takeIf { it.isNotEmpty() }?.let { list -> put("mentions", buildJsonArray { for (m in list) add(JsonPrimitive(m)) }) }
        invite?.let { put("invite", it.toJson()) }
        assignment?.let { put("assignment", it.toJson()) }
        roomLogo?.let { put("roomLogo", it.image?.toJson() ?: JsonNull) }
    }
    return Events.sign(
        secretKey = deviceSecretKey,
        kind = KIND_CHAT,
        createdAt = sentAt,
        tags = withRoomExpiration(listOf(listOf("d", address.id)), roomEnds),
        content = Nip44.encrypt(plaintext.toString(), address.key, nonce),
        auxRand = auxRand,
    )
}

private const val CHAT_ENVELOPE_2 = "kithmoot/v1/chat-envelope-2"
private val CHAT_ENVELOPE_2_SIGNATURE = Regex("[0-9a-f]{128}")

/** The message JSON, as the string the device signed, and that signature. */
private class ChatEnvelope2(val body: String, val sig: String)

/**
 * What the device signs inside envelope 2: SHA-256 of
 * `kithmoot/v1/chat-envelope-2:<room id>:<d tag>:<outer pubkey>:<created_at>:` and then [body],
 * every hex lower case. The room id is the room's epoch-0 id, as the credential names it.
 */
internal fun chatEnvelope2Digest(roomId: String, d: String, outer: String, createdAt: Long, body: String): ByteArray =
    Digests.sha256("$CHAT_ENVELOPE_2:${roomId.normaliseHex()}:${d.normaliseHex()}:${outer.normaliseHex()}:$createdAt:$body".toByteArray(Charsets.UTF_8))

/**
 * The body and signature of an envelope-2 plaintext, or null for envelope 1, which has no `v`.
 * Anything else carrying a `v` is refused by throwing: exactly `{"v":2,"body":<string>,"sig":<128
 * lower-case hex>}`. Mirrors `openEnvelope2` in src/chat.ts.
 */
private fun openChatEnvelope2(outer: JsonObject): ChatEnvelope2? {
    if (!outer.containsKey("v")) return null
    val v = outer["v"] as? JsonPrimitive
    val body = (outer["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val sig = (outer["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(outer.keys == setOf("v", "body", "sig") && v != null && !v.isString && v.content == "2" &&
        body != null && sig != null && CHAT_ENVELOPE_2_SIGNATURE.matches(sig)) { "malformed chat envelope" }
    return ChatEnvelope2(body, sig)
}

/** The device's signature over this event's outer key, room, channel and time. */
private fun chatEnvelope2Signed(envelope: ChatEnvelope2, credentialRoomId: String, event: NostrEvent, device: String): Boolean {
    val d = event.tagValue("d") ?: return false
    return Schnorr.verify(envelope.sig.hexToBytes(), chatEnvelope2Digest(credentialRoomId, d, event.pubkey, event.createdAt, envelope.body), device.hexToBytes())
}

/**
 * Reads a chat line, or returns null. Like the roster decoder it never throws:
 * this runs on every event a relay hands us, including deliberate rubbish.
 *
 * Two envelopes are read. In envelope 1 the device signs the event. In envelope 2 a key made
 * for that one message signs it, and the device's own signature rides inside the ciphertext;
 * see [openChatEnvelope2] and [chatEnvelope2Digest]. Either way the credential must name the
 * device the message names, never the event's signer. Only envelope 1 is written yet.
 */
fun decodeChatEvent(
    event: NostrEvent,
    roomId: String,
    roomKey: ByteArray,
    now: Long,
    policy: RoomPolicy? = null,
    channel: String? = null,
    credentialRoomId: String = roomId,
): ChatMessage? = try {
    val address = deriveChatChannel(roomId,roomKey,channel)
    when {
        event.kind != KIND_CHAT -> null
        event.tagValue("d")?.hexEquals(address.id) != true -> null
        !Events.verify(event) -> null
        else -> {
            val outer = Json.parseToJsonElement(Nip44.decrypt(event.content, address.key)).jsonObject
            val envelope2 = openChatEnvelope2(outer)
            val json = if (envelope2 != null) Json.parseToJsonElement(envelope2.body).jsonObject else outer
            // This is a boundary: `participant` is a free-text JSON field
            // with nothing forcing lower case. Canonicalise it here, once,
            // same as `decodeRosterEvent` - see `normaliseHex`.
            val participant = json.getValue("participant").jsonPrimitive.content.normaliseHex()
            val device = json.getValue("device").jsonPrimitive.content.normaliseHex()
            val credential = NostrEvent.fromJson(json.getValue("credential").jsonObject)
            val id = json.getValue("id").jsonPrimitive.content
            val body = json.getValue("text").jsonPrimitive.content
            val sentAt = json.getValue("sentAt").jsonPrimitive.long
            // Order only, so one that is not an integer within sentAt's second is dropped and the message kept.
            val sentAtMs = (json["sentAtMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                ?.takeIf { Math.floorDiv(it, 1000L) == sentAt }
            val proof = (json["proof"] as? JsonObject)?.let { KindredProof.fromJson(it) }
            val reaction = json["reaction"]?.let(::parseReaction)
            val check = verifyDeviceCredential(credential, credentialRoomId, sentAt)
            // One statement per message, checked on the keys as they arrived:
            // a reaction, an edit, a retraction and an invitation each say one
            // thing about one other message, and a payload carrying two of
            // them, or one beside conversation it has no business carrying,
            // is refused whole. Mirrors `decodeChatEvent` in src/chat.ts.
            val rawAttachments = json["attachments"] as? kotlinx.serialization.json.JsonArray
            require(rawAttachments == null || rawAttachments.size <= 4)
            val attachments = rawAttachments?.mapNotNull(::parseAttachment).orEmpty()
            val rawArtwork = json["artwork"] as? JsonArray
            require(rawArtwork == null || rawArtwork.size <= MAX_CHAT_ARTWORK)
            val artwork = rawArtwork?.mapNotNull(::parseArtwork).orEmpty()
            val statements = listOf("reaction", "replaces", "retracts", "invite", "assignment", "roomLogo").count { json.containsKey(it) }
            val conversationKeys = listOf("kind", "attachments", "artwork", "reply", "thread", "mentions")
            val hasStatementAlone = json.containsKey("reaction") || json.containsKey("retracts") || json.containsKey("invite") || json.containsKey("assignment") || json.containsKey("roomLogo")
            val roomLogo = if (json.containsKey("roomLogo")) roomLogoPayload(body, json["roomLogo"], channel) else null
            val assignment = (json["assignment"] as? JsonObject)?.let(NostrEvent::fromJson)
            val assignmentPayload = assignment?.let { assignmentPayload(it, credentialRoomId) }
            val invite = json["invite"]?.let(::parseInvite)
            val replaces = json["replaces"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
            val retracts = json["retracts"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
            val mentionsRaw = json["mentions"]
            val mentions = (mentionsRaw as? kotlinx.serialization.json.JsonArray)?.let(::normaliseMentions)?.takeIf { it.isNotEmpty() }
            when {
                json.containsKey("roomLogo") && roomLogo == null -> null
                json.containsKey("assignment") && (channel != ASSIGNMENT_CHANNEL || assignment == null || assignmentPayload == null || assignment.pubkey != participant || (assignmentPayload.device != null && assignmentPayload.device != device)) -> null
                statements > 1 -> null
                hasStatementAlone && conversationKeys.any { json.containsKey(it) } -> null
                json.containsKey("replaces") && listOf("kind", "reply", "thread").any { json.containsKey(it) } -> null
                json.containsKey("reaction") && reaction == null -> null
                json.containsKey("replaces") && !validMessageId(replaces) -> null
                json.containsKey("retracts") && !validMessageId(retracts) -> null
                json.containsKey("invite") && invite == null -> null
                mentionsRaw is kotlinx.serialization.json.JsonArray && mentionsRaw.size > MAX_MENTIONS -> null
                id.isEmpty() || id.length > 128 -> null
                body.isEmpty() || body.length > MAX_CHAT_TEXT_LENGTH -> null
                sentAt > now + MAX_CHAT_CLOCK_SKEW_SECONDS -> null
                check !is CredentialCheck.Valid -> null
                // The device that signed the message must be the device it names:
                // the event's own signer in envelope 1, the signature inside in
                // envelope 2 (over this event's outer key, room, channel and time,
                // with the same `sentAt` as the event).
                envelope2 == null && !device.hexEquals(event.pubkey) -> null
                envelope2 != null && (sentAt != event.createdAt || !chatEnvelope2Signed(envelope2, credentialRoomId, event, device)) -> null
                // And that credential must name that device and be signed by the
                // participant the message claims. Otherwise any member could put
                // words in anyone else's mouth.
                !check.device.hexEquals(device) -> null
                !check.participant.hexEquals(participant) -> null
                policy != null && !evaluateAccess(policy, participant, proof, sentAt, credentialRoomId).admitted -> null
                else -> ChatMessage(
                    id = id,
                    participant = participant,
                    device = device,
                    body = body,
                    sentAt = sentAt,
                    sentAtMs = sentAtMs,
                    name = dev.forgesworn.kithmoot.protocol.DisplayName.sanitise((json["name"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content),
                    reaction = reaction,
                    // A reference that does not check out is dropped and the
                    // message stays, which is what an older client shows.
                    reply = parseMessageRef(json["reply"]),
                    thread = parseMessageRef(json["thread"]),
                    replaces = replaces,
                    retracts = retracts,
                    mentions = mentions,
                    invite = invite,
                    assignment = assignment,
                    attachments = attachments,
                    artwork = artwork,
                    roomLogo = roomLogo,
                )
            }
        }
    }
} catch (_: Exception) {
    null
}
