package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import dev.forgesworn.kithmoot.cadence.CadenceRoomTransport
import dev.forgesworn.kithmoot.cadence.CadenceLeasePlan
import dev.forgesworn.kithmoot.cadence.CadenceOwnership
import dev.forgesworn.kithmoot.cadence.StoredCadenceLease
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

    @Test fun acceptedOwnWarningIsReadableBeforeAnyWorkCollectorRuns() = runTest {
        val relay = FakeRelay()
        val sender = session(room, alice, relay)
        sender.join(); sender.setCall(CallMembership(call, 0)); runCurrent()
        val notice = RecordingNotice(true, "34".repeat(16), 100)
        val details = RecordingCaptureNotice(notice.id, notice.version, "audio", alice.participant, alice.devicePubkey)
        val state = LiveRecordingState(SignedRecordingNotice(notice, signRecordingNotice(room.roomId, notice, alice.deviceSecretKey)),
            SignedRecordingCaptureNotice(details, signRecordingCaptureNotice(room.roomId, details, alice.deviceSecretKey)),
            call, alice.participant, alice.devicePubkey)
        val reader = work(alice, sender, relay, RecordingStopJournal(Stops()))
        assertTrue(sender.publishLiveRecordingConfirmed(state))
        assertTrue(reader.meeting.state.value.participantRecordings.isEmpty())
        assertTrue(sender.participantRecordingActive(notice, call))
        assertFalse(sender.participantRecordingActive(notice.copy(version = 101), call))
        assertFalse(sender.participantRecordingActive(notice, "56".repeat(16)))
        val off = notice.copy(on = false, version = 101)
        assertTrue(sender.publishLiveRecordingConfirmed(state.copy(recording = SignedRecordingNotice(off,
            signRecordingNotice(room.roomId, off, alice.deviceSecretKey)), capture = null)))
        assertTrue(reader.meeting.state.value.participantRecordings.isEmpty())
        assertFalse(sender.participantRecordingActive(notice, call))
        reader.close(); sender.leave()
    }

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

    @Test fun retainedStopCannotBePublishedAsAnotherPersonWithTheSameDevice() = runTest {
        val relay = FakeRelay(); val a = session(room, alice, relay)
        a.join(); a.setCall(CallMembership(call, 0)); runCurrent()
        val store = Stops(); val original = work(alice, a, relay, RecordingStopJournal(store))
        relay.confirmsPublications = false
        var id: String? = null
        assertFailsWith<IllegalStateException> { original.startRecording("audio", onArmed = { id = it }) }
        original.close(); a.leave(); runCurrent()
        relay.confirmsPublications = true
        val changed = Fixtures.primary(room, 3, 2)
        assertEquals(alice.devicePubkey, changed.devicePubkey)
        assertNotEquals(alice.participant, changed.participant)
        val b = session(room, changed, relay); b.join(); runCurrent()
        val wrongAccount = work(changed, b, relay, RecordingStopJournal(store))
        assertEquals(id, wrongAccount.recordingStopPending.value)
        val error = assertFailsWith<IllegalStateException> { wrongAccount.stopRecording(id!!) }
        assertTrue(error.message!!.contains("original account"))
        assertTrue(b.participantRecordings.value.isEmpty())
        assertNotNull(RecordingStopJournal(store).pending(room.roomId, alice.devicePubkey, alice.devicePubkey))
        wrongAccount.close(); b.leave(); runCurrent()
        val restoredSession = session(room, alice, relay); restoredSession.join(); runCurrent()
        val restored = work(alice, restoredSession, relay, RecordingStopJournal(store))
        restored.stopRecording(id!!); runCurrent()
        assertNull(RecordingStopJournal(store).pending(room.roomId, alice.devicePubkey, alice.devicePubkey))
        assertEquals(alice.participant, restoredSession.participantRecordings.value["$call:${alice.devicePubkey}"]!!.state.recorder)
        restored.close(); restoredSession.leave()
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

    @Test fun delegatedQuietScheduleKeepsParticipantWarningsConfirmedWithoutChatCounters() = runTest {
        val relay = FakeRelay()
        val policy = RoomPolicy(KindredTier.OPEN, null, listOf(alice.participant, bob.participant), quiet = true)
        var quietWrites = 0
        var boxCalls = 0
        val quiet = QuietTransport(relay.transport(), room.roomKey, alice.participant,
            policy.members!!, 0, backgroundScope, now = { currentTime / 1000 },
            slotOffset = { 0 }, reservedCounters = { (0 until 8).toSet() }, onState = { quietWrites++ })
        val lease = StoredCadenceLease(CadenceLeasePlan("a".repeat(52), room.roomId, room.roomId,
            1, alice.devicePubkey, "22".repeat(16), 1, "11".repeat(16), "{}", 0, 2, 0, 8),
            CadenceOwnership.BOX_OWNED, CadenceReceipt("status", "22".repeat(16), 1,
                "active", 0, 0, 2, 0, emptyList(), emptyList()), 0)
        val cadence = CadenceRoomTransport(quiet, backgroundScope, leaseAt = { lease },
            retain = { boxCalls++; error("Recording warning used chat retention") },
            queue = { _, _ -> boxCalls++; error("Recording warning used a box queue receipt") },
            release = { boxCalls++; error("Recording warning released a queued chat") },
            ownershipGeneration = { 0 }, now = { currentTime / 1000 })
        val sender = session(room, alice, relay, policy = policy, transport = cadence)
        val recipient = session(room, bob, relay, policy = policy)
        sender.join(); recipient.join()
        sender.setCall(CallMembership(call, 0)); recipient.setCall(CallMembership(call, 0)); runCurrent()
        val journal = RecordingStopJournal(Stops())
        val recorder = work(alice, sender, relay, journal)
        assertFalse(recorder.moderator)
        val notice = recorder.startRecording("gallery"); runCurrent()
        val flowKey = "$call:${alice.devicePubkey}"
        repeat(90) {
            advanceTimeBy(30_000)
            sender.announce(); recipient.announce(); runCurrent()
            assertTrue(sender.participantRecordingActive(notice, call))
            assertEquals(notice, recipient.participantRecordings.value[flowKey]!!.state.recording.notice)
        }
        assertEquals(2_700_000, currentTime)
        relay.confirmsPublications = false
        assertFailsWith<IllegalStateException> { recorder.stopRecording(notice.id) }
        assertEquals(notice.id, recorder.recordingStopPending.value)
        assertFalse(sender.participantRecordingActive(notice, call))
        val retained = journal.pending(room.roomId, alice.devicePubkey, alice.devicePubkey)!!
        relay.confirmsPublications = true
        recorder.stopRecording(notice.id); runCurrent()
        assertEquals(retained, recipient.participantRecordings.value[flowKey]!!.state.recording)
        assertNull(recorder.recordingStopPending.value)
        assertEquals(0, boxCalls)
        assertEquals(0, quiet.pending)
        assertEquals(0, quietWrites)
        assertTrue(relay.published.none { it.kind == 1059 || it.kind == KIND_CHAT })
        recorder.close(); sender.leave(); recipient.leave(); quiet.stop()
    }
}
