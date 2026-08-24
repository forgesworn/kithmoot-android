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
    data class Event(val subscriptionId: String, val event: NostrEvent) : RelayMessage
    data class EndOfStoredEvents(val subscriptionId: String) : RelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage

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

    /**
     * Parses one frame. **Never throws**: a relay is an untrusted stranger, and
     * one malformed frame must not be able to tear down the socket that carries
     * everybody else's presence.
     */
    fun parse(raw: String): RelayMessage = try {
        val frame = json.parseToJsonElement(raw) as? JsonArray ?: return RelayMessage.Unknown(raw)
        when (frame.getOrNull(0)?.jsonPrimitive?.content) {
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

            else -> RelayMessage.Unknown(raw)
        }
    } catch (_: Exception) {
        RelayMessage.Unknown(raw)
    }
}
