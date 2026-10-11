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
        callId: String = call.id, captureMode: String = "gallery"): LiveRecordingState {
        val notice = RecordingNotice(on, who.devicePubkey.take(32), version)
        val capture = RecordingCaptureNotice(notice.id, version, captureMode, who.participant, who.devicePubkey)
        return LiveRecordingState(SignedRecordingNotice(notice,
            signRecordingNotice(room.roomId, notice, who.deviceSecretKey, ByteArray(32))),
            if (on) SignedRecordingCaptureNotice(capture,
                signRecordingCaptureNotice(room.roomId, capture, who.deviceSecretKey, ByteArray(32))) else null,
            callId, who.participant, who.devicePubkey)
    }

    private fun received(who: RoomIdentity, state: LiveRecordingState = warning(who),
        at: Long = 0, activeCall: String? = state.call, profile: Int? = 4) =
        ReceivedMemberRecording(state, who.participant, who.devicePubkey, activeCall, profile, at)

    @Test fun stoppingOneRecorderPreservesTheOthersWarningAndConsent() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        assertTrue(meeting.receiveMember(received(first)))
        val firstConsent = assertNotNull(meeting.state.value.recordingConsentKey(0))
        assertTrue(meeting.receiveMember(received(second, warning(second, version = 9000))))
        assertEquals(2, meeting.state.value.recordingWarnings(0).size)
        assertNotEquals(firstConsent, meeting.state.value.recordingConsentKey(0))
        assertTrue(meeting.receiveMember(received(first, warning(first, on = false, version = 18), activeCall = null)))
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
        assertTrue(meeting.receiveMember(received(first, on)))
        assertFalse(meeting.receiveMember(received(first, warning(first, captureMode = "speaker"), at = 1)))
        assertEquals("gallery", meeting.state.value.recordingWarnings(0).single().capture?.capture)
        assertTrue(meeting.receiveMember(received(first, warning(first, on = false, version = 18))))
        assertFalse(meeting.receiveMember(received(first, on, at = 2)))
        assertTrue(meeting.state.value.recordingWarnings(2).isEmpty())
        assertNull(meeting.state.value.recordingConsentKey(2))
    }

    @Test fun heartbeatRefreshRetainsConsentAndWarningsAreScopedToAdvertisedCalls() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        val otherCall = "cd".repeat(16)
        assertTrue(meeting.receiveMember(received(first)))
        assertTrue(meeting.receiveMember(received(second, warning(second, callId = otherCall))))
        val before = meeting.state.value.recordingConsentKey(0)
        assertTrue(meeting.receiveMember(received(first, at = 20)))
        assertEquals(before, meeting.state.value.recordingConsentKey(20))
        assertEquals(0L, meeting.state.value.memberRecordings.getValue(first.devicePubkey).since)
        assertEquals(20L, meeting.state.value.memberRecordings.getValue(first.devicePubkey).heardAt)
        assertEquals(second.devicePubkey, meeting.state.value.recordingWarnings(20, setOf(otherCall)).single().device)
        assertTrue(meeting.state.value.recordingWarnings(20, emptySet()).isEmpty())
    }

    @Test fun wrongContextAndForgedSignaturesNeverCreateWarnings() {
        val meeting = RoomMeeting(room.roomId, null, { 0 })
        val valid = received(first)
        for (bad in listOf(valid.copy(participant = second.participant), valid.copy(device = second.devicePubkey),
            valid.copy(call = "cd".repeat(16)), valid.copy(profile = 3),
            valid.copy(state = valid.state.copy(recording = valid.state.recording.copy(sig = "00".repeat(64))))))
            assertFalse(meeting.receiveMember(bad))
        assertTrue(meeting.state.value.memberRecordings.isEmpty())
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
        assertTrue(reader.memberRecordings.value.isEmpty())
        reader.onRosterEvent(presence(first))
        reader.onRosterEvent(presence(second, warning(second, version = 9000)))
        assertEquals(2, reader.memberRecordings.value.size)
        val storage = object : AssignmentStorage {
            override suspend fun load(): String? = null
            override suspend fun save(encrypted: String) {}
        }
        // No followMemberRecordings collector is installed or advanced here.
        val work = RoomWork(room.roomId, room.roomKey, me, relay.transport(), storage, backgroundScope, now = { 0 })
        val snapshot = assertNotNull(work.recordingSnapshot(reader))
        assertEquals(2, snapshot.recordingWarnings(0, reader.advertisedRecordingCalls()).size)
        assertFalse(work.moderator)
        reader.onRosterEvent(presence(first, warning(first, on = false, version = 18), onCall = null))
        reader.onRosterEvent(presence(first))
        val latest = assertNotNull(work.recordingSnapshot(reader))
        assertEquals(second.devicePubkey, latest.recordingWarnings(0, reader.advertisedRecordingCalls()).single().device)
        assertFalse(latest.memberRecordings.getValue(first.devicePubkey).state.recording.notice.on)
        val another = Fixtures.room(8)
        val wrongRoom = session(another, Fixtures.primary(another, 7, 8), relay)
        assertNull(work.recordingSnapshot(wrongRoom))
        work.close(); wrongRoom.leave(); reader.leave()
    }
}
