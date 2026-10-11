package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ParticipantRecordingTest {
    private val room = "ab".repeat(32)
    private val call = "12".repeat(16)
    private val key = ByteArray(32) { 8 }
    private val device = Schnorr.publicKeyHex(key)
    private val person = "cd".repeat(32)
    private fun warning(on: Boolean = true): LiveRecordingState {
        val notice = RecordingNotice(on, "34".repeat(16), 100)
        val capture = RecordingCaptureNotice(notice.id, notice.version, "gallery", person, device)
        return LiveRecordingState(SignedRecordingNotice(notice, signRecordingNotice(room, notice, key)),
            if (on) SignedRecordingCaptureNotice(capture, signRecordingCaptureNotice(room, capture, key)) else null,
            call, person, device)
    }

    @Test fun ownDeviceWarningRoundTripsWithoutRoomAuthority() {
        val state = warning()
        assertEquals(state, LiveRecordingState.fromJson(state.toJson()))
        assertTrue(verifyParticipantRecordingState(room, state, person, device, call))
        assertFalse(verifyParticipantRecordingState("ef".repeat(32), state, person, device, call))
        assertFalse(verifyParticipantRecordingState(room, state, "ef".repeat(32), device, call))
        assertFalse(verifyParticipantRecordingState(room, state, person, "ef".repeat(32), call))
        assertFalse(verifyParticipantRecordingState(room, state, person, device, "56".repeat(16)))
        assertFalse(verifyParticipantRecordingState(room, state, person, device, null))
    }

    @Test fun ownStopCanBeConfirmedAfterLeavingButCannotStopAnotherDevice() {
        val state = warning(false)
        assertTrue(verifyParticipantRecordingState(room, state, person, device, null))
        assertTrue(verifyParticipantRecordingState(room, state, person, device, "56".repeat(16)))
        assertFalse(verifyParticipantRecordingState(room, state, person, "ef".repeat(32), null))
        assertFalse(verifyParticipantRecordingState(room, state.copy(recording = state.recording.copy(sig = "00".repeat(64))), person, device, null))
    }

    @Test fun incompletePublisherMetadataAndMismatchedCaptureAreRejected() {
        val state = warning()
        for (missing in listOf("call", "recorder", "device", "capture"))
            assertNull(LiveRecordingState.fromJson(JsonObject(state.toJson() - missing)))
        assertThrows(IllegalArgumentException::class.java) {
            state.copy(capture = state.capture!!.copy(notice = state.capture!!.notice.copy(device = "ef".repeat(32))))
        }
    }

    @Test fun independentlyVerifiesEveryTypeScriptSignedLayoutAndStop() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/participant-recording.json")).use { it.readBytes() }
        val fixture = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        fun text(key: String) = fixture.getValue(key).jsonPrimitive.content
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(5, cases.size)
        for (row in cases) {
            val encoded = row.jsonObject.getValue("state").jsonObject
            val state = requireNotNull(LiveRecordingState.fromJson(encoded))
            assertEquals(encoded, state.toJson())
            assertTrue(verifyParticipantRecordingState(text("roomId"), state, text("participant"), text("device"), text("call")))
            assertFalse(verifyParticipantRecordingState(text("roomId"), state, person, text("device"), text("call")))
            assertFalse(verifyParticipantRecordingState(text("roomId"), state, text("participant"), "ef".repeat(32), text("call")))
        }
    }
}
