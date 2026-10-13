package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRecordingPublicationTest {
    private val room = Fixtures.room()
    private val owner = Fixtures.primary(room, 1, 2)
    private fun state(on: Boolean = true, version: Long = 17): LiveRecordingState {
        val notice = RecordingNotice(on, "12".repeat(16), version)
        val details = RecordingCaptureNotice(notice.id, version, "gallery", owner.participant, owner.devicePubkey)
        return LiveRecordingState(SignedRecordingNotice(notice, signRecordingNotice(room.roomId, notice, Fixtures.key(1))),
            if (on) SignedRecordingCaptureNotice(details, signRecordingCaptureNotice(room.roomId, details, Fixtures.key(1))) else null)
    }
    private class DispatchTransport(private val base: RoomTransport) : RoomTransport by base {
        var beforeDispatch: suspend () -> Unit = {}
        var beforeReceipt: suspend () -> Unit = {}
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
            stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
            beforeDispatch()
            val confirmed = base.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            beforeReceipt()
            return confirmed
        }
    }

    @Test fun exactRelayAcknowledgementArmsTheCompleteWarningAndHeartbeatsCarryIt() = runTest {
        val relay = FakeRelay()
        val recorder = session(room, owner, relay, authority = owner.participant)
        recorder.join(); runCurrent()
        assertTrue(recorder.publishLiveRecordingConfirmed(state()))
        assertEquals(state().recording.notice, recorder.liveRecording.value!!.state.recording.notice)
        recorder.announce()
        val entry = decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!
        assertEquals(4, entry.recordingProfile)
        assertEquals(state().capture!!.notice, entry.liveRecording!!.capture!!.notice)
        assertTrue(verifyLiveRecordingState(room.roomId, entry.liveRecording!!, owner.participant))
        assertTrue(recorder.publishLiveRecordingConfirmed(state(on = false, version = 18)))
        recorder.announce()
        assertFalse(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!.liveRecording!!.recording.notice.on)
        assertFailsWith<IllegalStateException> { recorder.publishLiveRecordingConfirmed(state()) }
        recorder.leave()
    }

    @Test fun relayRefusalNeverArmsOrCarriesTheWarning() = runTest {
        val relay = FakeRelay().apply { confirmsPublications = false }
        val recorder = session(room, owner, relay, authority = owner.participant)
        recorder.join(); runCurrent()
        assertFalse(recorder.publishLiveRecordingConfirmed(state()))
        assertNull(recorder.liveRecording.value)
        recorder.announce()
        assertNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!.liveRecording)
        recorder.leave()
    }

    @Test fun cancellationAtDispatchPreventsTheOfferAndCancellationAfterAckCannotArmCapture() = runTest {
        val relay = FakeRelay()
        val dispatch = DispatchTransport(relay.transport())
        val recorder = session(room, owner, relay, authority = owner.participant, transport = dispatch)
        recorder.join(); runCurrent()
        var allowed = true
        dispatch.beforeDispatch = { allowed = false }
        val count = relay.published.size
        assertFailsWith<PublicationNotOfferedException> { recorder.publishLiveRecordingConfirmed(state()) { allowed } }
        assertEquals(count, relay.published.size)
        assertNull(recorder.liveRecording.value)
        allowed = true
        dispatch.beforeDispatch = {}
        dispatch.beforeReceipt = { allowed = false }
        assertFalse(recorder.publishLiveRecordingConfirmed(state()) { allowed })
        assertNull(recorder.liveRecording.value)
        recorder.announce()
        assertNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!.liveRecording)
        recorder.leave()
    }

    @Test fun leavingBeforeDispatchInvalidatesThePendingPresence() = runTest {
        val relay = FakeRelay()
        val dispatch = DispatchTransport(relay.transport())
        val recorder = session(room, owner, relay, authority = owner.participant, transport = dispatch)
        recorder.join(); runCurrent()
        dispatch.beforeDispatch = { recorder.leave() }
        assertFailsWith<PublicationNotOfferedException> { recorder.publishLiveRecordingConfirmed(state()) }
        assertNull(recorder.liveRecording.value)
        assertTrue(relay.published.all { decodeRosterEvent(it, room.roomId, room.roomKey, currentTime / 1000)?.liveRecording == null })
    }

    @Test fun aSecureRoomUpdateNeverCarriesAnOldOnIntoTheSuccessorEpoch() = runTest {
        val relay = FakeRelay()
        val recorder = session(room, owner, relay, authority = owner.participant,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        recorder.join(); runCurrent()
        assertTrue(recorder.publishLiveRecordingConfirmed(state()))
        relay.publish(encodeRekeyEvent(room.roomId, Fixtures.key(1), EpochKeys(0, room.roomId, room.roomKey),
            RoomEpoch(1, ByteArray(32) { 44 }), listOf(owner.devicePubkey), emptyList(), 0))
        runCurrent()
        val next = recorder.epochKeys()
        assertEquals(1, next.epoch)
        recorder.announce()
        assertNull(decodeRosterEvent(relay.published.last(), next.id, next.key, currentTime / 1000, room.roomId)!!.liveRecording)
        recorder.leave()
    }

    @Test fun aQueuedWarningCannotMoveToANewEpochWhileWaitingForAnotherReceipt() = runTest {
        val relay = FakeRelay()
        val dispatch = DispatchTransport(relay.transport())
        val recorder = session(room, owner, relay, authority = owner.participant, transport = dispatch,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        recorder.join(); runCurrent()
        val receipt = CompletableDeferred<Unit>()
        dispatch.beforeReceipt = { receipt.await() }
        val first = async { recorder.publishLiveRecordingConfirmed(state()) }
        runCurrent()
        val second = async { runCatching { recorder.publishLiveRecordingConfirmed(state(version = 19)) } }
        runCurrent()
        relay.publish(encodeRekeyEvent(room.roomId, Fixtures.key(1), EpochKeys(0, room.roomId, room.roomKey),
            RoomEpoch(1, ByteArray(32) { 44 }), listOf(owner.devicePubkey), emptyList(), 0))
        runCurrent()
        assertEquals(1, recorder.epochKeys().epoch)
        receipt.complete(Unit)
        assertFalse(first.await())
        assertIs<IllegalStateException>(second.await().exceptionOrNull())
        assertNull(recorder.liveRecording.value)
        recorder.leave()
    }

    @Test fun aNewerOffReceivedWhileWaitingForAckCannotBeOverwrittenByOn() = runTest {
        val relay = FakeRelay()
        val dispatch = DispatchTransport(relay.transport())
        val recorder = session(room, owner, relay, authority = owner.participant, transport = dispatch)
        recorder.join(); runCurrent()
        val other = Fixtures.secondary(room, owner, 30)
        val off = state(on = false, version = 18)
        dispatch.beforeReceipt = {
            recorder.onRosterEvent(encodeRosterEvent(RosterEntry(other.participant, other.devicePubkey, other.credential,
                updatedAt = currentTime / 1000, recordingProfile = 3, liveRecording = off),
                room.roomId, room.roomKey, other.deviceSecretKey))
        }
        assertFalse(recorder.publishLiveRecordingConfirmed(state()))
        assertEquals(off, recorder.liveRecording.value!!.state)
        recorder.announce()
        assertNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!.liveRecording)
        recorder.leave()
    }

    @Test fun aTransportGenerationChangeAfterAckCannotArmCapture() = runTest {
        val relay = FakeRelay()
        val dispatch = DispatchTransport(relay.transport())
        val recorder = session(room, owner, relay, authority = owner.participant, transport = dispatch)
        recorder.join(); runCurrent()
        dispatch.beforeReceipt = { relay.transport().beginRekey(); relay.transport().completeRekey() }
        assertFalse(recorder.publishLiveRecordingConfirmed(state()))
        assertNull(recorder.liveRecording.value)
        recorder.announce()
        assertNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!.liveRecording)
        recorder.leave()
    }

    @Test fun anExpiredPresenceIsNotOfferedEvenWhenItsCredentialStillHasTime() = runTest {
        val relay = FakeRelay()
        val dispatch = DispatchTransport(relay.transport())
        val recorder = session(room, owner, relay, authority = owner.participant, transport = dispatch)
        recorder.join(); runCurrent()
        dispatch.beforeDispatch = { advanceTimeBy(Fixtures.QUIET.presenceTtlSeconds * 1000) }
        val count = relay.published.size
        assertFailsWith<PublicationNotOfferedException> { recorder.publishLiveRecordingConfirmed(state()) }
        assertEquals(count, relay.published.size)
        assertNull(recorder.liveRecording.value)
        recorder.leave()
    }

    @Test fun fortyFiveMinutesOfLiveWarningsSpendNoQuietChatCountersOrQueueWrites() = runTest {
        val relay = FakeRelay()
        var writes = 0
        val quiet = QuietTransport(relay.transport(), room.roomKey, owner.participant, listOf(owner.participant),
            0, backgroundScope, ticking = false, now = { currentTime / 1000 }, onState = { writes++ })
        val recorder = session(room, owner, relay, authority = owner.participant, transport = quiet)
        recorder.join(); runCurrent()
        repeat(10) { index ->
            if (index > 0) advanceTimeBy(300_000)
            assertTrue(recorder.publishLiveRecordingConfirmed(state()))
        }
        assertEquals(2_700_000, currentTime)
        assertEquals(0, writes)
        assertTrue(quiet.exportState().used.isEmpty())
        assertTrue(quiet.exportState().queued.isEmpty())
        assertEquals(0, quiet.pending)
        assertTrue(recorder.publishLiveRecordingConfirmed(state(on = false, version = 18)))
        recorder.leave()
        quiet.stop()
    }
    @Test fun aFailedStopRetiresTheOldOnBeforeAnotherHeartbeat() = runTest {
        val relay = FakeRelay()
        val recorder = session(room, owner, relay, authority = owner.participant)
        recorder.join(); runCurrent()
        assertTrue(recorder.publishLiveRecordingConfirmed(state()))
        relay.confirmsPublications = false
        assertFalse(recorder.publishLiveRecordingConfirmed(state(on = false, version = 18)))
        recorder.announce()
        assertNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!.liveRecording)
        recorder.leave()
    }

    @Test fun anUnreadableOwnerGuardRetiresTheWarningButPresenceContinues() = runTest {
        val relay = FakeRelay()
        val recorder = session(room, owner, relay, authority = owner.participant)
        recorder.join(); runCurrent()
        var unreadable = false
        assertTrue(recorder.publishLiveRecordingConfirmed(state()) {
            check(!unreadable) { "Capture ownership is unavailable" }
            true
        })
        unreadable = true
        val count = relay.published.size
        recorder.announce()
        assertEquals(count + 1, relay.published.size)
        assertNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, currentTime / 1000)!!.liveRecording)
        recorder.leave()
    }

    @Test fun credentialExpiryRetiresHeartbeatAndDirectCapturePermission() = runTest {
        val shortOwner = PrimaryIdentity.create(room.roomId, expiresAt = 2, createdAt = 0,
            participantSecretKey = Fixtures.key(1), deviceSecretKey = Fixtures.key(2))
        val relay = FakeRelay()
        val recorder = session(room, shortOwner, relay, authority = owner.participant)
        recorder.join(); runCurrent()
        val call = CallMembership("ab".repeat(16), 0)
        recorder.setCall(call)
        assertTrue(recorder.publishLiveRecordingConfirmed(state()))
        assertTrue(recorder.recordingCaptureAllowed(call.id, requireLiveWarnings = false))
        advanceTimeBy(2_000)
        assertFalse(recorder.recordingCaptureAllowed(call.id, requireLiveWarnings = false))
        recorder.announce()
        assertNull(assertNotNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, 0)).liveRecording)
        recorder.leave()
    }

    @Test fun credentialExpiryWhileAwaitingAcknowledgementCannotArmTheWarning() = runTest {
        val shortOwner = PrimaryIdentity.create(room.roomId, expiresAt = 2, createdAt = 0,
            participantSecretKey = Fixtures.key(1), deviceSecretKey = Fixtures.key(2))
        val relay = FakeRelay()
        val dispatch = DispatchTransport(relay.transport())
        val recorder = session(room, shortOwner, relay, authority = owner.participant, transport = dispatch)
        recorder.join(); runCurrent()
        dispatch.beforeReceipt = { advanceTimeBy(2_000) }
        assertFalse(recorder.publishLiveRecordingConfirmed(state()))
        assertNull(recorder.liveRecording.value)
        recorder.leave()
    }

    @Test fun roomEndRetiresHeartbeatAndDirectCapturePermission() = runTest {
        val relay = FakeRelay()
        val recorder = session(room, owner, relay, authority = owner.participant, ends = 2)
        recorder.join(); runCurrent()
        val call = CallMembership("ab".repeat(16), 0)
        recorder.setCall(call)
        assertTrue(recorder.publishLiveRecordingConfirmed(state()))
        advanceTimeBy(2_000)
        assertFalse(recorder.recordingCaptureAllowed(call.id, requireLiveWarnings = false))
        recorder.announce()
        assertNull(assertNotNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, 0)).liveRecording)
        recorder.leave()
    }

    @Test fun signedAdmissionExpiryRetiresTheWarningAndCaptureWhileCredentialRemainsValid() = runTest {
        val issuer = Fixtures.key(8)
        val policy = RoomPolicy(KindredTier.KITH, listOf(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(issuer)))
        val proof = issueKindredProof(issuer, owner.participant, KindredTier.KITH, room.roomId, expiresAt = 2)
        val relay = FakeRelay()
        val recorder = session(room, owner, relay, authority = owner.participant, policy = policy, proof = proof)
        recorder.join(); runCurrent()
        val call = CallMembership("ab".repeat(16), 0)
        recorder.setCall(call)
        assertTrue(recorder.publishLiveRecordingConfirmed(state()))
        assertTrue(recorder.recordingCaptureAllowed(call.id, requireLiveWarnings = false))
        advanceTimeBy(2_000)
        assertIs<CredentialCheck.Valid>(verifyDeviceCredential(owner.credential, room.roomId, currentTime / 1000))
        assertFalse(recorder.recordingCaptureAllowed(call.id, requireLiveWarnings = false))
        recorder.announce()
        assertNull(assertNotNull(decodeRosterEvent(relay.published.last(), room.roomId, room.roomKey, 0)).liveRecording)
        recorder.leave()
    }

}
