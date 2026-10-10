package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.*

/** An exact signed message, bound to the original room and sending identity.
 * A recording owner must persist this before handing it to the outbox. It
 * contains no room credentials or room keys and cannot authorise another
 * message. Construction is restricted to this app module and authenticated
 * journal restoration; publication still checks the live room's authority. */
@OptIn(ExperimentalStdlibApi::class)
@ConsistentCopyVisibility
data class PreparedRoomChat internal constructor(
    val room: String,
    val participant: String,
    val device: String,
    val pending: PendingChatOutbox.Pending,
) {
    init {
        val hex = Regex("[0-9a-f]{64}")
        require(room.matches(hex) && participant.matches(hex) && device.matches(hex))
        require(pending.epochId.matches(hex) && pending.event.pubkey == device)
        require(pending.state == PendingChatState.WAITING)
        require(pending.text.length in 1..MAX_CHAT_TEXT_LENGTH && pending.messageId.matches(Regex("[0-9a-f]{32}")))
        require(pending.event.kind == KIND_CHAT && Events.verify(pending.event))
        require(pending.event.toJson().toString().toByteArray().size <= PendingChatOutbox.MAX_EVENT_BYTES)
    }
}

internal fun PreparedRoomChat.toJson() = buildJsonObject {
    put("room", room); put("participant", participant); put("device", device)
    put("epoch", pending.epochId); put("event", pending.event.toJson())
    put("editable", pending.editable); put("text", pending.text); put("messageId", pending.messageId)
}

internal fun preparedRoomChatFromJson(value: JsonElement): PreparedRoomChat {
    val obj = value.jsonObject
    fun string(key: String) = obj.getValue(key).jsonPrimitive.content
    return PreparedRoomChat(string("room"), string("participant"), string("device"),
        PendingChatOutbox.Pending(string("epoch"), NostrEvent.fromJson(obj.getValue("event")),
            editable = obj.getValue("editable").jsonPrimitive.boolean,
            text = string("text"), messageId = string("messageId")))
}
