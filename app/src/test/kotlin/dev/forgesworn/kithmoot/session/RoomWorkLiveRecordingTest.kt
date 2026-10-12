package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkLiveRecordingTest {
    private val room = Fixtures.room()
    private val owner = Fixtures.primary(room, 1, 2)
    private val storage = object : AssignmentStorage {
        override suspend fun load(): String? = null
        override suspend fun save(encrypted: String) {}
    }
    private class Stops : RoomStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }
    private fun TestScope.work(sender: RoomSession, relay: FakeRelay, stops: RecordingStopJournal) =
        RoomWork(room.roomId, room.roomKey, owner, relay.transport(), storage, backgroundScope,
            now = { currentTime / 1000 }, nowMs = { currentTime }, authority = owner.participant,
            authoritySecretKey = Fixtures.key(1), recordingStops = stops,
            liveRecordingPublisher = { state, allowed -> sender.publishLiveRecordingConfirmed(state, allowed) })

    @Test fun startupRefreshAndStopUseCompleteLivePresenceWithoutQuietSlots() = runTest {
        val relay = FakeRelay()
        var writes = 0
        val quiet = QuietTransport(relay.transport(), room.roomKey, owner.participant, listOf(owner.participant),
            0, backgroundScope, ticking = false, now = { currentTime / 1000 }, onState = { writes++ })
        val sender = session(room, owner, relay, authority = owner.participant, transport = quiet)
        val reader = session(room, Fixtures.primary(room, 3, 4), relay, authority = owner.participant)
        sender.join(); reader.join(); runCurrent()
        val stops = RecordingStopJournal(Stops())
        val work = work(sender, relay, stops)
        val on = work.startRecording("gallery")
        runCurrent()
        assertEquals(on, reader.liveRecording.value!!.state.recording.notice)
        assertEquals("gallery", reader.liveRecording.value!!.state.capture!!.notice.capture)
        assertIs<RecordingView.On>(work.meeting.state.value.recordingView(0))
        repeat(9) { advanceTimeBy(300_000); runCurrent() }
        assertEquals(2_700_000, currentTime)
        assertEquals(2700, reader.liveRecording.value!!.sentAt)
        assertEquals(0, writes)
        assertEquals(0, quiet.pending)
        assertTrue(quiet.exportState().used.isEmpty())
        assertTrue(relay.published.none { it.kind == KIND_CHAT })
        work.stopRecording(on.id); runCurrent()
        assertFalse(reader.liveRecording.value!!.state.recording.notice.on)
        assertNull(reader.liveRecording.value!!.state.capture)
        assertNull(stops.pending(room.roomId, owner.devicePubkey, owner.participant))
        work.close(); sender.leave(); reader.leave(); quiet.stop()
    }

    @Test fun refusalRetainsExactOffAndProcessRecoveryCanRetryItOverLivePresence() = runTest {
        val relay = FakeRelay().apply { confirmsPublications = false }
        val sender = session(room, owner, relay, authority = owner.participant)
        sender.join(); runCurrent()
        val bytes = Stops()
        val stops = RecordingStopJournal(bytes)
        val work = work(sender, relay, stops)
        var id: String? = null
        assertFailsWith<IllegalStateException> { work.startRecording("audio", onArmed = { id = it }) }
        assertNull(work.meeting.state.value.recording)
        val retained = stops.pending(room.roomId, owner.devicePubkey, owner.participant)!!
        assertEquals(id, retained.notice.id)
        assertFalse(retained.notice.on)
        work.close()
        relay.confirmsPublications = true
        val recoveredStops = RecordingStopJournal(bytes)
        val recovered = work(sender, relay, recoveredStops)
        recovered.stopRecording(id!!)
        assertEquals(retained, sender.liveRecording.value!!.state.recording)
        assertNull(recoveredStops.pending(room.roomId, owner.devicePubkey, owner.participant))
        recovered.close(); sender.leave()
    }

    @Test fun callCompatibilityChecksEachAuthenticatedDeviceAndRejectsDowngrades() = runTest {
        val relay = FakeRelay()
        val sender = session(room, owner, relay, authority = owner.participant)
        val peer = Fixtures.primary(room, 3, 4)
        val call = "12".repeat(16)
        fun deliver(profile: Int?, callId: String? = call, left: Boolean = false) {
            sender.onRosterEvent(encodeRosterEvent(RosterEntry(peer.participant, peer.devicePubkey, peer.credential,
                updatedAt = 0, recordingProfile = profile, left = left,
                call = callId?.let { CallMembership(it, 0) }), room.roomId, room.roomKey, peer.deviceSecretKey))
        }
        deliver(null, null)
        assertTrue(sender.liveRecordingCompatible(call))
        deliver(2, "34".repeat(16))
        assertTrue(sender.liveRecordingCompatible(call))
        deliver(2)
        assertFalse(sender.liveRecordingCompatible(call))
        deliver(3)
        assertTrue(sender.liveRecordingCompatible(call))
        deliver(null)
        assertFalse(sender.liveRecordingCompatible(call))
        deliver(null, left = true)
        assertTrue(sender.liveRecordingCompatible(call))
        sender.leave()
    }
}
