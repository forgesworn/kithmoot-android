package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** Profile 3 carries authority warnings; profile 4 also identifies a recording
 * participant, original call and signing device. Membership is verified against
 * authenticated presence, never inferred from these self-asserted fields. */
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
            require(call?.matches(Regex("[0-9a-f]{32}")) == true)
            require(recorder?.matches(Regex("[0-9a-f]{64}")) == true)
            require(device?.matches(Regex("[0-9a-f]{64}")) == true)
            capture?.notice?.let { require(it.recorder == recorder && it.device == device) }
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("recording", Json.parseToJsonElement(encodeRecordingOp(recording)))
        capture?.let { put("capture", Json.parseToJsonElement(encodeRecordingCaptureOp(it))) }
        call?.let { put("call", it) }
        recorder?.let { put("recorder", it) }
        device?.let { put("device", it) }
    }

    companion object {
        fun fromJson(value: JsonElement?): LiveRecordingState? = runCatching {
            val objectValue = value as? JsonObject ?: return null
            if (objectValue.keys.any { it !in setOf("recording", "capture", "call", "recorder", "device") }) return null
            val recording = decodeRecordingOp(objectValue.getValue("recording").toString()) ?: return null
            val capture = objectValue["capture"]?.let { decodeRecordingCaptureOp(it.toString()) ?: return null }
            val member = listOf("call", "recorder", "device").any { it in objectValue }
            fun text(key: String): String? = (objectValue[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (member && listOf("call", "recorder", "device").any { text(it) == null }) return null
            LiveRecordingState(recording, capture, text("call"), text("recorder"), text("device"))
        }.getOrNull()
    }
}

/** Legacy authority verification has no authenticated participant context and
 * therefore cannot adopt a participant-scoped warning. */
fun verifyLiveRecordingState(roomId: String, state: LiveRecordingState, authority: String): Boolean =
    state.call == null && verifyLiveRecordingSignatures(roomId, state, authority)

/** Call only after authenticating and admitting the outer roster entry. Off
 * remains publishable after leaving its original call, but cannot name another
 * participant or device. This grants no meeting or moderation authority. */
fun verifyLiveRecordingState(roomId: String, state: LiveRecordingState,
    participant: String, device: String, call: String?, profile: Int?, authority: String? = null): Boolean {
    val member = state.call != null
    if (member && (profile != 4 || (state.recording.notice.on && state.call != call) ||
            state.recorder != participant || state.device != device)) return false
    return (member && verifyLiveRecordingSignatures(roomId, state, device)) ||
        (authority?.let { verifyLiveRecordingSignatures(roomId, state, it) } == true)
}

private fun verifyLiveRecordingSignatures(roomId: String, state: LiveRecordingState, key: String): Boolean =
    verifyRecordingNotice(roomId, state.recording.notice, state.recording.sig, key) &&
        (state.capture?.let { verifyRecordingCaptureNotice(roomId, it.notice, it.sig, key) } ?: true)
