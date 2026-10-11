package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.*

class LiveRecordingReceptionTest {
    private val secret = Fixtures.key(1)
    private val authority = Schnorr.publicKeyHex(secret)
    private val room = Fixtures.room()
    @Test fun captureReadsDoNotWaitForSessionCollectorsAndTrackCallCompatibility() = runTest {
        val reader = session(room, Fixtures.primary(room, 3, 4), FakeRelay(), authority = authority)
        val caller = Fixtures.primary(room, 1, 2)
        val call = CallMembership("ab".repeat(16), 0)
        reader.join()
        reader.setCall(call)
        fun presence(profile: Int, left: Boolean = false) {
            reader.onRosterEvent(encodeRosterEvent(RosterEntry(caller.participant, caller.devicePubkey, caller.credential,
                updatedAt = 0, recordingProfile = profile, call = call, left = left), room.roomId, room.roomKey, caller.deviceSecretKey))
        }
        presence(3)
        // Reproduces the lock ordering at a synchronous StateFlow collector:
        // the session remains locked while another thread checks capture.
        val field = RoomSession::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = field.get(reader) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        try {
            lock.lock()
            try {
                assertTrue(worker.submit<Boolean> { reader.recordingCaptureAllowed(call.id, true) }.get(1, TimeUnit.SECONDS))
            } finally { lock.unlock() }
            presence(2)
            assertFalse(reader.recordingCaptureAllowed(call.id, true))
            assertTrue(reader.recordingCaptureAllowed(call.id, false))
            presence(2, left = true)
            assertTrue(reader.recordingCaptureAllowed(call.id, true))
            reader.setCall(call.copy(id = "cd".repeat(16)))
            assertFalse(reader.recordingCaptureAllowed(call.id, false))
            reader.leave()
            assertFalse(reader.recordingCaptureAllowed("cd".repeat(16), false))
        } finally { worker.shutdownNow() }
    }
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
