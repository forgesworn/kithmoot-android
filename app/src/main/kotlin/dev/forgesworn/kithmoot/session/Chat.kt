package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import kotlinx.serialization.json.Json
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
const val KIND_CHAT: Int = 20463

/** A line of chat, attributed to the person rather than the device that typed it. */
data class ChatMessage(
    val id: String,
    val participant: String,
    val device: String,
    val body: String,
    val sentAt: Long,
)

fun encodeChatEvent(
    body: String,
    participant: String,
    credential: NostrEvent,
    roomId: String,
    roomKey: ByteArray,
    deviceSecretKey: ByteArray,
    sentAt: Long,
    nonce: ByteArray = Entropy.bytes(32),
    auxRand: ByteArray = Entropy.bytes(32),
): NostrEvent {
    val plaintext: JsonObject = buildJsonObject {
        put("participant", participant)
        put("credential", credential.toJson())
        put("body", body)
        put("sentAt", sentAt)
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
            val credential = NostrEvent.fromJson(json.getValue("credential").jsonObject)
            val check = verifyDeviceCredential(credential, roomId, now)
            when {
                check !is CredentialCheck.Valid -> null
                // The device that signed the event must be the device the
                // credential names, and that credential must be signed by the
                // participant the message claims. Otherwise any member could put
                // words in anyone else's mouth.
                !check.device.hexEquals(event.pubkey) -> null
                !check.participant.hexEquals(participant) -> null
                else -> ChatMessage(
                    id = event.id,
                    participant = participant,
                    device = event.pubkey.normaliseHex(),
                    body = json.getValue("body").jsonPrimitive.content,
                    sentAt = json.getValue("sentAt").jsonPrimitive.long,
                )
            }
        }
    }
} catch (_: Exception) {
    null
}
