package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.evaluateAccess
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
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

/** A line of chat, attributed to the person rather than the device that typed it. */
data class ChatMessage(
    val id: String,
    val participant: String,
    val device: String,
    val body: String,
    val sentAt: Long,
    val name: String? = null,
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
)

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
): NostrEvent {
    require(reaction == null || parseReaction(reaction.toJson()) != null) { "Invalid reaction" }
    require(listOfNotNull(reaction, replaces, retracts, invite).size <= 1) { "a message says one thing about another message, not two" }
    require(replaces == null || validMessageId(replaces)) { "an edit must name the message it replaces" }
    require(retracts == null || validMessageId(retracts)) { "a retraction must name the message it retracts" }
    require(mentions == null || mentions.size <= MAX_MENTIONS) { "a message names at most $MAX_MENTIONS participants" }
    val plaintext: JsonObject = buildJsonObject {
        put("id", id)
        put("participant", participant)
        put("device", Schnorr.publicKeyHex(deviceSecretKey))
        put("credential", credential.toJson())
        proof?.let { put("proof", it.toJson()) }
        put("text", body)
        put("sentAt", sentAt)
        reaction?.let { put("reaction", it.toJson()) }
        reply?.let { put("reply", it.toJson()) }
        thread?.let { put("thread", it.toJson()) }
        replaces?.let { put("replaces", it) }
        retracts?.let { put("retracts", it) }
        mentions?.takeIf { it.isNotEmpty() }?.let { list -> put("mentions", buildJsonArray { for (m in list) add(JsonPrimitive(m)) }) }
        invite?.let { put("invite", it.toJson()) }
    }
    return Events.sign(
        secretKey = deviceSecretKey,
        kind = KIND_CHAT,
        createdAt = sentAt,
        tags = listOf(listOf("d", roomId)),
        content = Nip44.encrypt(plaintext.toString(), roomKey, nonce),
        auxRand = auxRand,
    )
}

/**
 * Reads a chat line, or returns null. Like the roster decoder it never throws:
 * this runs on every event a relay hands us, including deliberate rubbish.
 */
fun decodeChatEvent(
    event: NostrEvent,
    roomId: String,
    roomKey: ByteArray,
    now: Long,
    policy: RoomPolicy? = null,
): ChatMessage? = try {
    when {
        event.kind != KIND_CHAT -> null
        event.tagValue("d")?.hexEquals(roomId) != true -> null
        !Events.verify(event) -> null
        else -> {
            val json = Json.parseToJsonElement(Nip44.decrypt(event.content, roomKey)).jsonObject
            // This is a boundary: `participant` is a free-text JSON field
            // with nothing forcing lower case. Canonicalise it here, once,
            // same as `decodeRosterEvent` - see `normaliseHex`.
            val participant = json.getValue("participant").jsonPrimitive.content.normaliseHex()
            val device = json.getValue("device").jsonPrimitive.content.normaliseHex()
            val credential = NostrEvent.fromJson(json.getValue("credential").jsonObject)
            val id = json.getValue("id").jsonPrimitive.content
            val body = json.getValue("text").jsonPrimitive.content
            val sentAt = json.getValue("sentAt").jsonPrimitive.long
            val proof = (json["proof"] as? JsonObject)?.let { KindredProof.fromJson(it) }
            val reaction = json["reaction"]?.let(::parseReaction)
            val check = verifyDeviceCredential(credential, roomId, sentAt)
            // One statement per message, checked on the keys as they arrived:
            // a reaction, an edit, a retraction and an invitation each say one
            // thing about one other message, and a payload carrying two of
            // them, or one beside conversation it has no business carrying,
            // is refused whole. Mirrors `decodeChatEvent` in src/chat.ts.
            val statements = listOf("reaction", "replaces", "retracts", "invite").count { json.containsKey(it) }
            val conversationKeys = listOf("kind", "attachments", "reply", "thread", "mentions")
            val hasStatementAlone = json.containsKey("reaction") || json.containsKey("retracts") || json.containsKey("invite")
            val invite = json["invite"]?.let(::parseInvite)
            val replaces = json["replaces"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
            val retracts = json["retracts"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
            val mentionsRaw = json["mentions"]
            val mentions = (mentionsRaw as? kotlinx.serialization.json.JsonArray)?.let(::normaliseMentions)?.takeIf { it.isNotEmpty() }
            when {
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
                // The device that signed the event must be the device the
                // credential names, and that credential must be signed by the
                // participant the message claims. Otherwise any member could put
                // words in anyone else's mouth.
                !check.device.hexEquals(event.pubkey) -> null
                !check.participant.hexEquals(participant) -> null
                !device.hexEquals(event.pubkey) -> null
                policy != null && !evaluateAccess(policy, participant, proof, sentAt, roomId).admitted -> null
                else -> ChatMessage(
                    id = id,
                    participant = participant,
                    device = device,
                    body = body,
                    sentAt = sentAt,
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
                )
            }
        }
    }
} catch (_: Exception) {
    null
}
