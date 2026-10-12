package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class LiveRecordingTest {
    private val secret = ByteArray(32) { 7 }
    private val authority = Schnorr.publicKeyHex(secret)
    private val room = "ab".repeat(32)
    private val notice = RecordingNotice(true, "12".repeat(16), 17)
    private val capture = RecordingCaptureNotice(notice.id, notice.version, "gallery", "aa".repeat(32), "bb".repeat(32))
    private val live = LiveRecordingState(
        SignedRecordingNotice(notice, signRecordingNotice(room, notice, secret, ByteArray(32))),
        SignedRecordingCaptureNotice(capture, signRecordingCaptureNotice(room, capture, secret, ByteArray(32))))

    @Test fun pairedWarningsRoundTripAndRequireBothAuthoritySignatures() {
        assertEquals(live.toJson(), LiveRecordingState.fromJson(live.toJson())!!.toJson())
        assertTrue(verifyLiveRecordingState(room, live, authority))
        assertFalse(verifyLiveRecordingState("cd".repeat(32), live, authority))
        val altered = live.copy(capture = live.capture!!.copy(notice = capture.copy(recorder = "cc".repeat(32))))
        assertFalse(verifyLiveRecordingState(room, altered, authority))
        assertFalse(verifyLiveRecordingState(room, live.copy(recording = live.recording.copy(sig = "00".repeat(64))), authority))
    }

    @Test fun missingMismatchedAndOffCaptureDetailsAreRejected() {
        assertNull(LiveRecordingState.fromJson(JsonObject(mapOf("recording" to live.toJson().getValue("recording")))))
        assertThrows(IllegalArgumentException::class.java) {
            live.copy(capture = live.capture!!.copy(notice = capture.copy(version = 18)))
        }
        val off = notice.copy(on = false, version = 18)
        val stopped = LiveRecordingState(SignedRecordingNotice(off, signRecordingNotice(room, off, secret, ByteArray(32))))
        assertTrue(verifyLiveRecordingState(room, stopped, authority))
        assertEquals(stopped.toJson(), LiveRecordingState.fromJson(stopped.toJson())!!.toJson())
        assertNull(LiveRecordingState.fromJson(JsonObject(stopped.toJson() + ("capture" to live.toJson().getValue("capture")))))
    }

    @Test fun onlyProfileThreeCarriesLiveStateAndLegacyEncodingStaysUnchanged() {
        val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))
        val entry = RosterEntry("aa".repeat(32), "bb".repeat(32), credential, updatedAt = 1_800_000_000)
        val encoded = entry.copy(recordingProfile = 3, liveRecording = live).toJson()
        assertEquals(encoded, RosterEntry.fromJson(encoded).toJson())
        assertEquals(live, RosterEntry.fromJson(encoded).liveRecording)
        assertEquals(entry.toJson(), entry.copy(liveRecording = live).toJson())
        assertNull(entry.copy(recordingProfile = 2, liveRecording = live).toJson()["liveRecording"])
    }
}
