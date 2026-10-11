package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemberRecordingReceptionTest {
    private val room = Fixtures.room()
    private val call = CallMembership("ab".repeat(16), 0)
    private val first = Fixtures.primary(room, 1, 2)
    private val second = Fixtures.primary(room, 3, 4)

    private fun warning(who: RoomIdentity, on: Boolean = true, version: Long = 17,
        callId: String = call.id, captureMode: String = "gallery",
        id: String = who.devicePubkey.take(32)): LiveRecordingState {
        val notice = RecordingNotice(on, id, version)
        val capture = RecordingCaptureNotice(notice.id, version, captureMode, who.participant, who.devicePubkey)
        return LiveRecordingState(SignedRecordingNotice(notice,
            signRecordingNotice(room.roomId, notice, who.deviceSecretKey, ByteArray(32))),
            if (on) SignedRecordingCaptureNotice(capture,
                signRecordingCaptureNotice(room.roomId, capture, who.deviceSecretKey, ByteArray(32))) else null,
            callId, who.participant, who.devicePubkey)
    }

    private fun received(who: RoomIdentity, state: LiveRecordingState = warning(who),
        at: Long = 0, activeCall: String? = state.call) =
        ReceivedLiveRecording(state, at, who.participant, who.devicePubkey, activeCall)

    @Test fun stoppingOneRecorderPreservesTheOthersWarningAndConsent() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        assertTrue(meeting.receiveParticipant(received(first)))
        val firstConsent = assertNotNull(meeting.state.value.recordingConsentKey(0))
        assertTrue(meeting.receiveParticipant(received(second, warning(second, version = 9000))))
        assertEquals(2, meeting.state.value.recordingWarnings(0).size)
        assertNotEquals(firstConsent, meeting.state.value.recordingConsentKey(0))
        assertTrue(meeting.receiveParticipant(received(first, warning(first, on = false, version = 18), activeCall = null)))
        val remaining = meeting.state.value.recordingWarnings(0).single()
        assertEquals(second.devicePubkey, remaining.device)
        assertIs<RecordingView.On>(remaining.view)
        assertNotNull(meeting.state.value.recordingConsentKey(0))
        assertNull(meeting.state.value.policy)
        assertNull(meeting.state.value.recording)
    }

    @Test fun anOldOnAndSameVersionChangedCaptureCannotReviveOrRewriteADeviceWarning() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        val on = warning(first)
        assertTrue(meeting.receiveParticipant(received(first, on)))
        assertFalse(meeting.receiveParticipant(received(first, warning(first, captureMode = "speaker"), at = 1)))
        assertEquals("gallery", meeting.state.value.recordingWarnings(0).single().capture?.capture)
        assertTrue(meeting.receiveParticipant(received(first, warning(first, on = false, version = 18))))
        assertFalse(meeting.receiveParticipant(received(first, on, at = 2)))
        assertTrue(meeting.state.value.recordingWarnings(2).isEmpty())
        assertNull(meeting.state.value.recordingConsentKey(2))
    }

    @Test fun heartbeatRefreshRetainsConsentAndWarningsAreScopedToAdvertisedCalls() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        val otherCall = "cd".repeat(16)
        assertTrue(meeting.receiveParticipant(received(first)))
        assertTrue(meeting.receiveParticipant(received(second, warning(second, callId = otherCall))))
        val before = meeting.state.value.recordingConsentKey(0)
        assertTrue(meeting.receiveParticipant(received(first, at = 20)))
        assertEquals(before, meeting.state.value.recordingConsentKey(20))
        assertEquals(0L, meeting.state.value.participantRecordings.getValue("${call.id}:${first.devicePubkey}").since)
        assertEquals(20L, meeting.state.value.participantRecordings.getValue("${call.id}:${first.devicePubkey}").heardAt)
        assertEquals(second.devicePubkey, meeting.state.value.recordingWarnings(20, setOf(otherCall)).single().device)
        assertTrue(meeting.state.value.recordingWarnings(20, emptySet()).isEmpty())
    }

    @Test fun captureModeChangeRequiresFreshConsentButEquivalentRefreshDoesNot() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        assertTrue(meeting.receiveParticipant(received(first)))
        val galleryConsent = assertNotNull(meeting.state.value.recordingConsentKey(0))
        assertTrue(meeting.receiveParticipant(received(first, warning(first, version = 18, captureMode = "speaker"))))
        val speakerConsent = assertNotNull(meeting.state.value.recordingConsentKey(0))
        assertNotEquals(galleryConsent, speakerConsent)
        assertTrue(meeting.receiveParticipant(received(first, warning(first, version = 19, captureMode = "speaker"))))
        assertEquals(speakerConsent, meeting.state.value.recordingConsentKey(0))
    }

    @Test fun anotherDeviceChoosingTheSameIdCannotAuthoriseTheOriginalDeviceCapture() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        val original = warning(first)
        val id = original.recording.notice.id
        assertTrue(meeting.receiveParticipant(received(first, original)))
        assertTrue(meeting.receiveParticipant(received(second, warning(second, id = id))))
        assertTrue(meeting.receiveParticipant(received(first, warning(first, on = false, version = 18))))
        val snapshot = meeting.state.value
        assertTrue(snapshot.activeRecording(id, 0))
        assertFalse(snapshot.activeRecording(id, 0, first.devicePubkey))
        assertTrue(snapshot.activeRecording(id, 0, second.devicePubkey))
        assertFalse(snapshot.activeRecording(original.recording.notice, 0, first.devicePubkey, call.id))
        assertTrue(snapshot.activeRecording(original.recording.notice, 0, second.devicePubkey, call.id))
    }

    @Test fun wrongContextAndForgedSignaturesNeverCreateWarnings() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        val valid = received(first)
        for (bad in listOf(valid.copy(participant = second.participant), valid.copy(device = second.devicePubkey),
            valid.copy(call = "cd".repeat(16)),
            valid.copy(state = valid.state.copy(recording = valid.state.recording.copy(sig = "00".repeat(64))))))
            assertFalse(meeting.receiveParticipant(bad))
        assertTrue(meeting.state.value.participantRecordings.isEmpty())
        assertNull(meeting.state.value.policy)
    }

    @Test fun signedPresenceIsAcceptedAndConsentReadsItBeforeAnyCollectorRuns() = runTest {
        val relay = FakeRelay()
        val me = Fixtures.primary(room, 5, 6)
        val reader = session(room, me, relay)
        reader.join()
        fun presence(who: RoomIdentity, state: LiveRecordingState = warning(who), profile: Int = 4,
            onCall: CallMembership? = call) = encodeRosterEvent(RosterEntry(who.participant, who.devicePubkey,
                who.credential, updatedAt = 0, recordingProfile = profile, liveRecording = state, call = onCall),
            room.roomId, room.roomKey, who.deviceSecretKey)
        reader.onRosterEvent(presence(first).copy(sig = "00".repeat(64)))
        reader.onRosterEvent(presence(first, profile = 3))
        reader.onRosterEvent(presence(first, onCall = call.copy(id = "cd".repeat(16))))
        assertTrue(reader.participantRecordings.value.isEmpty())
        reader.onRosterEvent(presence(first))
        reader.onRosterEvent(presence(second, warning(second, version = 9000)))
        assertEquals(2, reader.participantRecordings.value.size)
        val storage = object : AssignmentStorage {
            override suspend fun load(): String? = null
            override suspend fun save(encrypted: String) {}
        }
        // No followParticipantRecordings collector is installed or advanced here.
        val work = RoomWork(room.roomId, room.roomKey, me, relay.transport(), storage, backgroundScope, now = { 0 })
        val snapshot = assertNotNull(work.recordingSnapshot(reader))
        assertEquals(2, snapshot.recordingWarnings(0, reader.advertisedRecordingCalls()).size)
        assertFalse(work.moderator)
        reader.onRosterEvent(presence(first, warning(first, on = false, version = 18), onCall = null))
        reader.onRosterEvent(presence(first))
        val latest = assertNotNull(work.recordingSnapshot(reader))
        assertEquals(second.devicePubkey, latest.recordingWarnings(0, reader.advertisedRecordingCalls()).single().device)
        assertFalse(latest.participantRecordings.getValue("${call.id}:${first.devicePubkey}").state.recording.notice.on)
        val another = Fixtures.room(8)
        val wrongRoom = session(another, Fixtures.primary(another, 7, 8), relay)
        assertNull(work.recordingSnapshot(wrongRoom))
        work.close(); wrongRoom.leave(); reader.leave()
    }
}
