package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Profile 3 carries the authority's complete recording warning in live presence. */
data class LiveRecordingState(
    val recording: SignedRecordingNotice,
    val capture: SignedRecordingCaptureNotice? = null,
) {
    init {
        require(decodeRecordingOp(encodeRecordingOp(recording)) == recording)
        if (recording.notice.on) {
            require(capture != null && capture.notice.id == recording.notice.id &&
                capture.notice.version == recording.notice.version)
            require(decodeRecordingCaptureOp(encodeRecordingCaptureOp(capture)) == capture)
        } else require(capture == null)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("recording", Json.parseToJsonElement(encodeRecordingOp(recording)))
        capture?.let { put("capture", Json.parseToJsonElement(encodeRecordingCaptureOp(it))) }
    }

    companion object {
        fun fromJson(value: JsonElement?): LiveRecordingState? = runCatching {
            val objectValue = value as? JsonObject ?: return null
            if (objectValue.keys != setOf("recording") && objectValue.keys != setOf("recording", "capture")) return null
            val recording = decodeRecordingOp(objectValue.getValue("recording").toString()) ?: return null
            val capture = objectValue["capture"]?.let { decodeRecordingCaptureOp(it.toString()) ?: return null }
            LiveRecordingState(recording, capture)
        }.getOrNull()
    }
}

/** A device's presence signature never substitutes for the room authority. */
fun verifyLiveRecordingState(roomId: String, state: LiveRecordingState, authority: String): Boolean =
    verifyRecordingNotice(roomId, state.recording.notice, state.recording.sig, authority) &&
        (state.capture?.let { verifyRecordingCaptureNotice(roomId, it.notice, it.sig, authority) } ?: true)
