package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Frames a relay sends us. Anything we do not understand becomes [Unknown]. */
sealed interface RelayMessage {
    /** NIP-42 challenge. It is handled by [RelayPool], never surfaced to rooms. */
    data class Auth(val challenge: String) : RelayMessage
    data class Event(val subscriptionId: String, val event: NostrEvent) : RelayMessage
    data class EndOfStoredEvents(val subscriptionId: String) : RelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage
    data class NegentropyMessage(val subscriptionId: String, val payload: ByteArray) : RelayMessage
    data class NegentropyError(val subscriptionId: String, val message: String) : RelayMessage

    /** Not an error. Relays add frames, and a client that trips over one is broken. */
    data class Unknown(val raw: String) : RelayMessage
}

object RelayCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** `["EVENT", <event>]` */
    fun publishFrame(event: NostrEvent): String = buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(event.toJson())
    }.toString()

    /** `["REQ", <subscriptionId>, <filter>, ...]` */
    fun requestFrame(subscriptionId: String, filters: List<Filter>): String = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subscriptionId))
        for (filter in filters) add(filter.toJson())
    }.toString()

    /** `["CLOSE", <subscriptionId>]` */
    fun closeFrame(subscriptionId: String): String = buildJsonArray {
        add(JsonPrimitive("CLOSE"))
        add(JsonPrimitive(subscriptionId))
    }.toString()

    /** `["AUTH", <signed kind-22242 event>]` */
    fun authFrame(event: NostrEvent): String = buildJsonArray {
        add(JsonPrimitive("AUTH"))
        add(event.toJson())
    }.toString()

    /** `NEG-OPEN` is reachable only through RelayPool's Link/NIP-42 gate. */
    fun negOpenFrame(subscriptionId: String, filter: Filter, initial: ByteArray): String = buildJsonArray {
        add(JsonPrimitive("NEG-OPEN")); add(JsonPrimitive(subscriptionId)); add(filter.toJson()); add(JsonPrimitive(initial.hex()))
    }.toString()

    fun negMessageFrame(subscriptionId: String, payload: ByteArray): String = buildJsonArray {
        add(JsonPrimitive("NEG-MSG")); add(JsonPrimitive(subscriptionId)); add(JsonPrimitive(payload.hex()))
    }.toString()

    fun negCloseFrame(subscriptionId: String): String = buildJsonArray {
        add(JsonPrimitive("NEG-CLOSE")); add(JsonPrimitive(subscriptionId))
    }.toString()

    /**
     * Parses one frame. **Never throws**: a relay is an untrusted stranger, and
     * one malformed frame must not be able to tear down the socket that carries
     * everybody else's presence.
     */
    fun parse(raw: String): RelayMessage = try {
        val frame = json.parseToJsonElement(raw) as? JsonArray ?: return RelayMessage.Unknown(raw)
        when (frame.getOrNull(0)?.jsonPrimitive?.content) {
            "AUTH" -> frame.getOrNull(1)?.jsonPrimitive?.content
                ?.takeIf { it.toByteArray(Charsets.UTF_8).size in 1..512 && it.none(Char::isISOControl) }
                ?.let(RelayMessage::Auth) ?: RelayMessage.Unknown(raw)
            "EVENT" -> RelayMessage.Event(
                subscriptionId = frame[1].jsonPrimitive.content,
                event = NostrEvent.fromJson(frame[2].jsonObject),
            )

            "EOSE" -> RelayMessage.EndOfStoredEvents(frame[1].jsonPrimitive.content)

            "OK" -> RelayMessage.Ok(
                eventId = frame[1].jsonPrimitive.content,
                accepted = frame[2].jsonPrimitive.boolean,
                message = frame.getOrNull(3)?.jsonPrimitive?.content.orEmpty(),
            )

            "CLOSED" -> RelayMessage.Closed(
                subscriptionId = frame[1].jsonPrimitive.content,
                message = frame.getOrNull(2)?.jsonPrimitive?.content.orEmpty(),
            )

            "NOTICE" -> RelayMessage.Notice(frame.getOrNull(1)?.jsonPrimitive?.content.orEmpty())

            "NEG-MSG" -> frame.getOrNull(1)?.jsonPrimitive?.content?.let { id ->
                frame.getOrNull(2)?.jsonPrimitive?.content?.let { payload -> parseNegMessage(id, payload) }
            } ?: RelayMessage.Unknown(raw)

            "NEG-ERR" -> frame.getOrNull(1)?.jsonPrimitive?.content?.takeIf(::validSubscriptionId)
                ?.let { RelayMessage.NegentropyError(it, frame.getOrNull(2)?.jsonPrimitive?.content.orEmpty()) }
                ?: RelayMessage.Unknown(raw)

            else -> RelayMessage.Unknown(raw)
        }
    } catch (_: Exception) {
        RelayMessage.Unknown(raw)
    }

    private fun parseNegMessage(subscriptionId: String, encoded: String): RelayMessage.NegentropyMessage? {
        if (!validSubscriptionId(subscriptionId) || encoded.length !in 2..(Nip77Negentropy.MAX_MESSAGE_BYTES * 2) ||
            encoded.length % 2 != 0 || !encoded.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val payload = ByteArray(encoded.length / 2) { index -> encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return RelayMessage.NegentropyMessage(subscriptionId, payload)
    }

    private fun validSubscriptionId(value: String) = value.toByteArray(Charsets.UTF_8).size in 1..64 && value.none(Char::isISOControl)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
