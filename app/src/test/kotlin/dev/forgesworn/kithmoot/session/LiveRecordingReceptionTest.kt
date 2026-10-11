package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LiveRecordingReceptionTest {
    private val secret = Fixtures.key(1)
    private val authority = Schnorr.publicKeyHex(secret)
    private val room = Fixtures.room()
    private fun state(on: Boolean = true, version: Long = 17): LiveRecordingState {
        val notice = RecordingNotice(on, "12".repeat(16), version)
        val capture = RecordingCaptureNotice(notice.id, version, "gallery", authority, Schnorr.publicKeyHex(Fixtures.key(2)))
        return LiveRecordingState(SignedRecordingNotice(notice, signRecordingNotice(room.roomId, notice, secret, ByteArray(32))),
            if (on) SignedRecordingCaptureNotice(capture, signRecordingCaptureNotice(room.roomId, capture, secret, ByteArray(32))) else null)
    }

    @Test fun completeLiveStateUpdatesTheOrdinaryWarningAndRejectsPartialAuthority() {
        val news = mutableListOf<MeetingNews>()
        val meeting = RoomMeeting(room.roomId, authority, { 0 }, news::add)
        val live = state()
        assertFalse(meeting.receiveLive(live.copy(capture = live.capture!!.copy(sig = "00".repeat(64))), 0))
        assertNull(meeting.state.value.recording)
        assertNull(meeting.state.value.capture)
        assertTrue(meeting.receiveLive(live, 0))
        assertEquals(live.capture!!.notice, meeting.state.value.recordingCapture())
        assertIs<RecordingView.On>(meeting.state.value.recordingView(0))
        assertEquals(listOf(MeetingNews.RecordingOn), news)
    }

    @Test fun stoppedOrConflictingLiveStateCannotReviveOrRewriteTheWarning() {
        val meeting = RoomMeeting(room.roomId, authority, { 0 })
        val live = state()
        assertTrue(meeting.receiveLive(live, 0))
        val changed = live.capture!!.notice.copy(recorder = "cc".repeat(32))
        val conflict = live.copy(capture = SignedRecordingCaptureNotice(changed,
            signRecordingCaptureNotice(room.roomId, changed, secret, ByteArray(32))))
        assertFalse(meeting.receiveLive(conflict, 1))
        assertEquals(live.capture!!.notice, meeting.state.value.recordingCapture())
        assertTrue(meeting.receiveLive(state(on = false, version = 18), 1))
        assertNull(meeting.state.value.recordingCapture())
        assertFalse(meeting.receiveLive(live, 2))
        assertEquals(false, meeting.state.value.recording!!.notice.on)
    }

    @Test fun authenticatedPresenceRequiresAuthorityAndKeepsNewerOffAgainstReplay() = runTest {
        val owner = Fixtures.primary(room, 1, 2)
        val reader = session(room, Fixtures.primary(room, 3, 4), FakeRelay(), authority = authority)
        fun deliver(live: LiveRecordingState) {
            val entry = RosterEntry(owner.participant, owner.devicePubkey, owner.credential,
                updatedAt = 0, recordingProfile = 3, liveRecording = live)
            reader.onRosterEvent(encodeRosterEvent(entry, room.roomId, room.roomKey, owner.deviceSecretKey))
        }
        val live = state()
        deliver(live.copy(capture = live.capture!!.copy(sig = "00".repeat(64))))
        assertNull(reader.liveRecording.value)
        deliver(live)
        assertEquals(live, reader.liveRecording.value!!.state)
        val off = state(on = false, version = 18)
        deliver(off)
        deliver(live)
        assertEquals(off, reader.liveRecording.value!!.state)
        reader.leave()
    }
}
