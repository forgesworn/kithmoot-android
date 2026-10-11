package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** Profile 3 carries root warnings. Profile 4 also carries a call device's
 * own recording warning, without granting any room authority. */
data class LiveRecordingState(
    val recording: SignedRecordingNotice,
    val capture: SignedRecordingCaptureNotice? = null,
    val call: String? = null,
    val recorder: String? = null,
    val device: String? = null,
) {
    init {
        require(decodeRecordingOp(encodeRecordingOp(recording)) == recording)
        if (recording.notice.on) {
            require(capture != null && capture.notice.id == recording.notice.id &&
                capture.notice.version == recording.notice.version)
            require(decodeRecordingCaptureOp(encodeRecordingCaptureOp(capture)) == capture)
        } else require(capture == null)
        if (call != null || recorder != null || device != null) {
            require(call?.matches(Regex("[0-9a-f]{32}")) == true &&
                recorder?.matches(Regex("[0-9a-f]{64}")) == true && device?.matches(Regex("[0-9a-f]{64}")) == true)
            require(capture == null || (capture.notice.recorder == recorder && capture.notice.device == device))
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("recording", Json.parseToJsonElement(encodeRecordingOp(recording)))
        capture?.let { put("capture", Json.parseToJsonElement(encodeRecordingCaptureOp(it))) }
        call?.let { put("call", it); put("recorder", recorder); put("device", device) }
    }

    companion object {
        fun fromJson(value: JsonElement?): LiveRecordingState? = runCatching {
            val objectValue = value as? JsonObject ?: return null
            val member = objectValue.keys.any { it in setOf("call", "recorder", "device") }
            val keys = setOf("recording") + (if ("capture" in objectValue) setOf("capture") else emptySet()) +
                (if (member) setOf("call", "recorder", "device") else emptySet())
            if (objectValue.keys != keys) return null
            val recording = decodeRecordingOp(objectValue.getValue("recording").toString()) ?: return null
            val capture = objectValue["capture"]?.let { decodeRecordingCaptureOp(it.toString()) ?: return null }
            fun text(key: String): String? = (objectValue[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (member && listOf("call", "recorder", "device").any { text(it) == null }) return null
            LiveRecordingState(recording, capture, text("call"), text("recorder"), text("device"))
        }.getOrNull()
    }
}

/** A device's presence signature never substitutes for the room authority. */
fun verifyLiveRecordingState(roomId: String, state: LiveRecordingState, authority: String): Boolean =
    verifyRecordingNotice(roomId, state.recording.notice, state.recording.sig, authority) &&
        (state.capture?.let { verifyRecordingCaptureNotice(roomId, it.notice, it.sig, authority) } ?: true)

/** The caller must first authenticate and admit the enclosing roster. Off
 * can be confirmed after leaving a call, but can only stop this device's ID. */
fun verifyParticipantRecordingState(roomId: String, state: LiveRecordingState,
    participant: String, device: String, call: String?): Boolean =
    state.call != null && state.recorder == participant && state.device == device &&
        (!state.recording.notice.on || state.call == call) &&
        verifyLiveRecordingState(roomId, state, device)
