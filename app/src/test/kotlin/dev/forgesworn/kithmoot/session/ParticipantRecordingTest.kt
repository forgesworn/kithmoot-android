package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ParticipantRecordingTest {
    private val room = Fixtures.room()
    private val call = "12".repeat(16)
    private val alice = Fixtures.primary(room, 1, 2)
    private val bob = Fixtures.primary(room, 3, 4)
    private class Stops : RoomStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }
    private val storage = object : AssignmentStorage {
        override suspend fun load(): String? = null
        override suspend fun save(encrypted: String) {}
    }
    private fun TestScope.work(identity: RoomIdentity, sender: RoomSession, relay: FakeRelay, journal: RecordingStopJournal) =
        RoomWork(room.roomId, room.roomKey, identity, relay.transport(), storage, backgroundScope,
            now = { currentTime / 1000 }, nowMs = { currentTime }, recordingStops = journal,
            participantRecordingPublisher = { state, allowed -> sender.publishLiveRecordingConfirmed(state, allowed) },
            participantRecordingCall = { sender.currentCall()?.id },
            participantRecordingCompatible = { sender.currentCall()?.id?.let { sender.participantRecordingCompatible(it) } == true })

    @Test fun twoPeopleWithoutOwnerKeysRecordAndStopIndependently() = runTest {
        val relay = FakeRelay()
        val a = session(room, alice, relay); val b = session(room, bob, relay)
        a.join(); b.join(); a.setCall(CallMembership(call, 0)); b.setCall(CallMembership(call, 0)); runCurrent()
        val aw = work(alice, a, relay, RecordingStopJournal(Stops()))
        val bw = work(bob, b, relay, RecordingStopJournal(Stops()))
        aw.followParticipantRecordings(a.participantRecordings); bw.followParticipantRecordings(b.participantRecordings)
        assertFalse(aw.moderator); assertFalse(bw.moderator)
        assertTrue(aw.canRecord); assertTrue(bw.canRecord)
        val first = aw.startRecording("audio"); runCurrent()
        val second = bw.startRecording("gallery"); runCurrent()
        assertTrue(aw.meeting.state.value.activeRecording(first.id, 0))
        assertTrue(aw.meeting.state.value.activeRecording(second.id, 0))
        assertEquals(2, bw.meeting.state.value.recordingCaptures(0, setOf(call)).size)
        aw.stopRecording(first.id); runCurrent()
        assertFalse(bw.meeting.state.value.activeRecording(first.id, 0))
        assertTrue(bw.meeting.state.value.activeRecording(second.id, 0))
        assertEquals(listOf(second.id), bw.meeting.state.value.recordingCaptures(0, setOf(call)).map { it.id })
        assertEquals(RecordingView.Off, bw.meeting.state.value.recordingView(0, setOf("56".repeat(16))))
        bw.stopRecording(second.id); runCurrent()
        assertEquals(RecordingView.Off, aw.meeting.state.value.recordingView(0, setOf(call)))
        aw.close(); bw.close(); a.leave(); b.leave()
    }

    @Test fun refusedStartRetainsOwnSignedStopAcrossRestartAndAfterLeavingCall() = runTest {
        val relay = FakeRelay()
        val a = session(room, alice, relay)
        a.join(); a.setCall(CallMembership(call, 0)); runCurrent()
        val store = Stops(); val journal = RecordingStopJournal(store)
        val original = work(alice, a, relay, journal)
        relay.confirmsPublications = false
        var id: String? = null
        assertFailsWith<IllegalStateException> { original.startRecording("audio", onArmed = { id = it }) }
        assertTrue(a.participantRecordings.value.isEmpty())
        val retained = journal.pending(room.roomId, alice.devicePubkey, alice.devicePubkey)!!
        assertEquals(call, journal.pendingCall(room.roomId, alice.devicePubkey))
        original.close(); a.setCall(null); runCurrent()
        relay.confirmsPublications = true
        val restored = work(alice, a, relay, RecordingStopJournal(store))
        restored.stopRecording(id!!); runCurrent()
        assertEquals(retained, a.participantRecordings.value["$call:${alice.devicePubkey}"]!!.state.recording)
        assertNull(RecordingStopJournal(store).pending(room.roomId, alice.devicePubkey, alice.devicePubkey))
        assertNull(restored.recordingStopPending.value)
        restored.close(); a.leave()
    }

    @Test fun onlyOlderDevicesOnThisCallBlockParticipantRecording() = runTest {
        val relay = FakeRelay(); val a = session(room, alice, relay)
        a.join(); a.setCall(CallMembership(call, 0)); runCurrent()
        fun peer(profile: Int?, membership: CallMembership?) = a.onRosterEvent(encodeRosterEvent(
            RosterEntry(bob.participant, bob.devicePubkey, bob.credential, updatedAt = 0,
                recordingProfile = profile, call = membership), room.roomId, room.roomKey, bob.deviceSecretKey))
        val work = work(alice, a, relay, RecordingStopJournal(Stops()))
        peer(3, CallMembership(call, 0))
        assertFalse(a.participantRecordingCompatible(call))
        assertFailsWith<IllegalStateException> { work.startRecording("audio") }
        peer(3, null)
        assertTrue(a.participantRecordingCompatible(call))
        val notice = work.startRecording("audio")
        work.stopRecording(notice.id); work.close(); a.leave()
    }
}
