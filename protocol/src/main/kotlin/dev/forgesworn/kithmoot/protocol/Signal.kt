package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexEquals
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The signalling event, before it is wrapped. Never published as-is. */
const val KIND_SIGNAL: Int = 20462

/** NIP-59 ephemeral gift wrap, borrowed from NIP-AC so clients can share code. */
const val KIND_SIGNAL_WRAP: Int = 21059
const val SIGNAL_EXPIRATION_SECONDS: Long = 60
const val MAX_SIGNAL_WRAP_LENGTH: Int = 131_072
const val SIGNAL_PROFILE: String = "1"

/**
 * One piece of WebRTC negotiation: an offer, an answer, or a trickled candidate.
 *
 * This is the payload that must never sit readable on a relay - an SDP names
 * every local IP address the sender has, which is why signalling is wrapped per
 * peer while the roster is merely encrypted to the room.
 */
data class SignalBody(
    val type: String,
    val roomId: String,
    val sdp: String? = null,
    val candidate: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("roomId", roomId)
        if (sdp != null) put("sdp", sdp)
        if (candidate != null) put("candidate", candidate)
    }

    companion object {
        fun fromJson(json: JsonObject): SignalBody = SignalBody(
            type = json.getValue("type").jsonPrimitive.content,
            roomId = json.getValue("roomId").jsonPrimitive.content,
            sdp = json["sdp"]?.jsonPrimitive?.content,
            candidate = json["candidate"]?.jsonPrimitive?.content,
        )
    }
}

/** Both halves of a wrap: the signed inner event, and the wrap that carries it. */
data class WrappedSignal(val inner: NostrEvent, val wrap: NostrEvent)

/** A signal that came back out of a wrap, with the sender it was signed by. */
data class UnwrappedSignal(val from: String, val body: SignalBody, val id: String)

/**
 * Gift-wraps a signal for one peer.
 *
 * The inner event is signed by the sender, so the recipient knows who is
 * offering. The wrap is signed by a **fresh ephemeral key** and encrypted to
 * the recipient, so the relay learns only that somebody sent something to that
 * pubkey - not who, and not what.
 */
fun wrapSignal(
    body: SignalBody,
    senderSecretKey: ByteArray,
    recipientPubkey: String,
    ephemeralSecretKey: ByteArray = Entropy.bytes(32),
    createdAt: Long = System.currentTimeMillis() / 1000,
    innerAuxRand: ByteArray = Entropy.bytes(32),
    outerAuxRand: ByteArray = Entropy.bytes(32),
    nonce: ByteArray = Entropy.bytes(32),
): WrappedSignal {
    val recipientTag = listOf(listOf("p", recipientPubkey))
    val inner = Events.sign(
        secretKey = senderSecretKey,
        kind = KIND_SIGNAL,
        createdAt = createdAt,
        tags = recipientTag + listOf(listOf("call-id", body.roomId), listOf("alt", "KithMoot call signalling"), listOf("kithmoot", SIGNAL_PROFILE)),
        content = body.toJson().toString(),
        auxRand = innerAuxRand,
    )
    val conversationKey = Nip44.conversationKey(ephemeralSecretKey, recipientPubkey.hexToBytes())
    val wrap = Events.sign(
        secretKey = ephemeralSecretKey,
        kind = KIND_SIGNAL_WRAP,
        createdAt = createdAt,
        tags = recipientTag + listOf(listOf("expiration", (createdAt + SIGNAL_EXPIRATION_SECONDS).toString())),
        content = Nip44.encrypt(inner.toCompactJson(), conversationKey, nonce),
        auxRand = outerAuxRand,
    )
    return WrappedSignal(inner = inner, wrap = wrap)
}

/**
 * Opens a wrap addressed to us, or returns null.
 *
 * Like the roster decoder, this never throws: unwrapping runs on every wrap a
 * relay hands us, including wraps meant for other people and wraps from
 * strangers, and none of those may be allowed to kill a subscription.
 */
fun unwrapSignal(
    wrap: NostrEvent,
    recipientSecretKey: ByteArray,
    roomId: String,
    /** Unix seconds. Defaults to the real clock; injectable so a test - or a
     *  vector, which is stamped with a fixed time - is not at the mercy of one. */
    now: Long = System.currentTimeMillis() / 1000,
    /** How far either side of [now] a signal may be stamped before it is
     *  refused. See [SIGNAL_MAX_AGE_SECONDS]. */
    maxAgeSeconds: Long = SIGNAL_MAX_AGE_SECONDS,
): UnwrappedSignal? {
    return try {
    val recipientPubkey = Schnorr.publicKeyHex(recipientSecretKey)
    when {
        wrap.kind != KIND_SIGNAL_WRAP || wrap.content.length > MAX_SIGNAL_WRAP_LENGTH -> null
        wrap.tags.size > 16 || wrap.tags.any { it.size > 8 || it.any { value -> value.length > 2048 } } -> null
        !Events.verify(wrap) -> null
        else -> {
            val conversationKey = Nip44.conversationKey(recipientSecretKey, wrap.pubkey.hexToBytes())
            val decrypted = NostrEvent.fromJson(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(Nip44.decrypt(wrap.content, conversationKey))
                    .jsonObject,
            )
            val inner = if (decrypted.kind == 13) {
                if (!Events.verify(decrypted)) return null
                val sealKey = Nip44.conversationKey(recipientSecretKey, decrypted.pubkey.hexToBytes())
                val rumor = kotlinx.serialization.json.Json.parseToJsonElement(Nip44.decrypt(decrypted.content, sealKey)).jsonObject
                val event = NostrEvent.fromJson(JsonObject(rumor + ("sig" to JsonPrimitive(""))))
                if (event.kind != KIND_SIGNAL || !event.pubkey.hexEquals(decrypted.pubkey)) return null
                if (Events.eventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content) != event.id) return null
                event
            } else {
                if (decrypted.kind != KIND_SIGNAL || !Events.verify(decrypted)) return null
                decrypted
            }
            val body = SignalBody.fromJson(
                kotlinx.serialization.json.Json.parseToJsonElement(inner.content).jsonObject,
            )
            when {
                inner.kind != KIND_SIGNAL -> null
                inner.tagValue("p")?.hexEquals(recipientPubkey) != true -> null
                // Staleness, checked on the *inner* event: it is the one the
                // sending device signed, so its timestamp cannot be restamped
                // by whoever replays the wrap. See [SIGNAL_MAX_AGE_SECONDS]
                // for why the window is symmetric.
                kotlin.math.abs(now - inner.createdAt) > maxAgeSeconds -> null
                // A signal is only meaningful in the room it names: a body
                // replayed into a different room is refused outright. Hex
                // identifiers compared case-insensitively - see
                // `vectors/README.md`.
                !body.roomId.hexEquals(roomId) -> null
                inner.tags.count { it.firstOrNull() == "call-id" } > 1 -> null
                inner.tagValue("call-id")?.hexEquals(roomId) == false -> null
                // `from` is a device pubkey entering the system off the
                // wire - the peer map it gets looked up in is keyed by the
                // same normalised form roster decode produces, so this
                // must match. See `normaliseHex`.
                else -> UnwrappedSignal(from = inner.pubkey.normaliseHex(), body = body, id = inner.id)
            }
        }
    }
} catch (_: Exception) {
    null
}

}
