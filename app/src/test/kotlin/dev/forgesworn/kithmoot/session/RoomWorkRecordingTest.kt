package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkRecordingTest {
    private val room="a".repeat(64)
    private val key=ByteArray(32) {9}
    private val sk=ByteArray(32) {3}
    private val authority=Schnorr.publicKeyHex(sk)
    private val host=PrimaryIdentity.create(room,1000,100,ByteArray(32) {1},ByteArray(32) {4})
    private class Transport:RoomTransport {
        val sent=mutableListOf<NostrEvent>()
        var rejectAt=Int.MAX_VALUE
        var stored=emptyList<NostrEvent>()
        var beforeGuard:(()->Unit)?=null
        override fun publish(event:NostrEvent) {sent+=event}
        override fun subscribe(filters:List<Filter>)=MutableSharedFlow<NostrEvent>()
        override suspend fun queryStored(filters:List<Filter>,timeoutMs:Long)=stored
        override suspend fun publishConfirmed(event:NostrEvent,timeoutMs:Long):Boolean {
            sent+=event
            return sent.size!=rejectAt
        }
        override suspend fun publishConfirmedGuarded(event:NostrEvent,generation:Long,stillAllowed:()->Boolean,timeoutMs:Long):Boolean {
            beforeGuard?.invoke()
            if(!stillAllowed())throw PublicationNotOfferedException()
            return publishConfirmed(event,timeoutMs)
        }
    }
    private val storage=object:AssignmentStorage {
        override suspend fun load():String?=null
        override suspend fun save(encrypted:String) {}
    }
    private fun TestScope.work(t:Transport,secret:ByteArray?=sk,stops:RecordingStopJournal?=null,clock:()->Long={200})=RoomWork(room,key,host,t,storage,
        backgroundScope,now=clock,nowMs={200_000},authority=authority,authoritySecretKey=secret,recordingStops=stops)
    private fun body(event:NostrEvent)=assertNotNull(decodeChatEvent(event,room,key,200,channel="control")).body

    private class StopStorage:RoomStorage {
        var bytes:ByteArray?=null
        var fail=false
        override fun read()=bytes?.clone()
        override fun write(value:ByteArray) { check(!fail) { "Synthetic stop journal failure" };bytes=value.clone() }
        override fun reset() {bytes=null}
    }

    @Test fun forget_at_transport_dispatch_prevents_running_notice_and_late_journal_resurrection()=runTest {
        val retained=StopStorage();val stops=RecordingStopJournal(retained)
        val t=Transport().apply {beforeGuard={stops.forgetRoom(room)}}
        val work=work(t,stops=stops)
        assertFailsWith<PublicationNotOfferedException> {work.startRecording("audio")}
        assertTrue(t.sent.isEmpty())
        assertNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        t.beforeGuard=null
        assertFailsWith<IllegalStateException> {work.startRecording("audio")}
        assertTrue(t.sent.isEmpty());work.close()
    }

    @Test fun armed_stop_survives_close_and_reopen_without_replaying_on()=runTest {
        val retained=StopStorage();val firstTransport=Transport()
        val first=work(firstTransport,stops=RecordingStopJournal(retained))
        val on=first.startRecording("audio")
        val off=assertNotNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        assertFalse(off.notice.on);assertEquals(on.id,off.notice.id);assertTrue(off.notice.version>on.version)
        assertNull(first.recordingStopPending.value,"A live capture must not show a stopped warning")
        first.close()
        assertFailsWith<IllegalStateException> {first.stopRecording(on.id)}
        val t=Transport();val second=work(t,stops=RecordingStopJournal(retained))
        assertEquals(on.id,second.recordingStopPending.value)
        assertFailsWith<IllegalStateException> {second.startRecording("audio")}
        assertTrue(t.sent.isEmpty())
        t.rejectAt=1
        assertFailsWith<IllegalStateException> {second.stopRecording(on.id)}
        assertEquals(off,decodeRecordingOp(body(t.sent.single())))
        assertEquals(on.id,second.recordingStopPending.value)
        second.close()
        val thirdTransport=Transport();val third=work(thirdTransport,stops=RecordingStopJournal(retained))
        third.stopRecording(on.id)
        assertEquals(off,decodeRecordingOp(body(thirdTransport.sent.single())))
        assertNull(third.recordingStopPending.value)
        assertNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        val next=third.startRecording("audio")
        assertTrue(next.version>off.notice.version)
        third.close()
    }

    @Test fun failed_on_offer_keeps_armed_stop_and_failed_journal_write_offers_nothing()=runTest {
        val retained=StopStorage().apply {fail=true};val t=Transport()
        val work=work(t,stops=RecordingStopJournal(retained))
        assertFailsWith<IllegalStateException> {work.startRecording("audio")}
        assertTrue(t.sent.isEmpty());assertNull(work.meeting.state.value.recording)
        retained.fail=false;t.rejectAt=2
        assertFailsWith<IllegalStateException> {work.startRecording("audio")}
        val off=assertNotNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        assertEquals(off.notice.id,work.recordingStopPending.value)
        t.rejectAt=Int.MAX_VALUE
        work.stopRecording(off.notice.id)
        assertNull(work.recordingStopPending.value);work.close()
    }

    @Test fun replay_of_newer_recording_retires_old_stop_without_stopping_or_refreshing_it()=runTest {
        val retained=StopStorage();val first=work(Transport(),stops=RecordingStopJournal(retained))
        first.startRecording("audio");first.close()
        val off=assertNotNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        val newer=RecordingNotice(true,"ef".repeat(16),off.notice.version+10)
        val body=encodeRecordingOp(SignedRecordingNotice(newer,signRecordingNotice(room,newer,sk)))
        val event=encodeChatEvent(body,host.participant,host.credential,room,key,host.deviceSecretKey,200,channel="control")
        val t=Transport().apply {stored=listOf(event)}
        val restored=work(t,stops=RecordingStopJournal(retained))
        restored.open();runCurrent()
        assertEquals(newer,restored.meeting.state.value.recording?.notice)
        val offered=t.sent.toList() // Opening requests the current catalogue.
        restored.stopRecording(off.notice.id)
        assertEquals(offered,t.sent,"The stale stop must offer no recording control")
        assertNull(restored.recordingStopPending.value)
        assertNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        assertEquals(newer,restored.meeting.state.value.recording?.notice)
        restored.close()
    }

    @Test fun retained_signed_off_can_be_reenveloped_without_the_authority_secret()=runTest {
        val retained=StopStorage();val first=work(Transport(),stops=RecordingStopJournal(retained))
        val on=first.startRecording("audio");first.close()
        val off=assertNotNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        val t=Transport();val restored=work(t,secret=null,stops=RecordingStopJournal(retained))
        assertFalse(restored.moderator)
        restored.stopRecording(on.id)
        assertEquals(off,decodeRecordingOp(body(t.sent.single())))
        assertNull(restored.recordingStopPending.value)
        assertFailsWith<IllegalStateException> {restored.startRecording("audio")}
        restored.close()
    }

    @Test fun replay_of_matching_off_does_not_erase_retry_before_publication_confirmation()=runTest {
        val retained=StopStorage();val first=work(Transport(),stops=RecordingStopJournal(retained))
        val on=first.startRecording("audio");first.close()
        val off=assertNotNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        val event=encodeChatEvent(encodeRecordingOp(off),host.participant,host.credential,room,key,host.deviceSecretKey,200,channel="control")
        val t=Transport().apply {stored=listOf(event)}
        val restored=work(t,secret=null,stops=RecordingStopJournal(retained))
        restored.open();runCurrent()
        assertEquals(off.notice,restored.meeting.state.value.recording?.notice)
        assertEquals(on.id,restored.recordingStopPending.value)
        assertEquals(off,RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        t.rejectAt=t.sent.size+1
        assertFailsWith<IllegalStateException> {restored.stopRecording(on.id)}
        assertEquals(on.id,restored.recordingStopPending.value)
        assertEquals(off,RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        t.rejectAt=Int.MAX_VALUE
        restored.stopRecording(on.id)
        assertNull(restored.recordingStopPending.value)
        assertNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        restored.close()
    }

    @Test fun expired_device_credential_cannot_complete_a_retained_stop()=runTest {
        val retained=StopStorage();var time=200L;val t=Transport()
        val work=work(t,stops=RecordingStopJournal(retained),clock={time})
        val on=work.startRecording("audio")
        val offered=t.sent.size
        time=2_000L
        assertFailsWith<IllegalStateException> {work.stopRecording(on.id)}
        assertEquals(offered,t.sent.size)
        assertEquals(on.id,work.recordingStopPending.value)
        assertNotNull(RecordingStopJournal(retained).pending(room,host.devicePubkey,authority))
        work.close()
    }

    @Test fun confirmed_details_precede_running_notice_and_identify_this_device()=runTest {
        val t=Transport(); val work=work(t)
        val on=work.startRecording("audio")
        val details=assertNotNull(decodeRecordingCaptureOp(body(t.sent[0])))
        val signed=assertNotNull(decodeRecordingOp(body(t.sent[1])))
        assertTrue(verifyRecordingCaptureNotice(room,details.notice,details.sig,authority))
        assertTrue(verifyRecordingNotice(room,signed.notice,signed.sig,authority))
        assertEquals(on,signed.notice)
        assertEquals(RecordingCaptureNotice(on.id,on.version,"audio",host.participant,host.devicePubkey),details.notice)
        assertEquals(details.notice,work.meeting.state.value.recordingCapture())
        work.close()
    }

    @Test fun failed_publication_never_authorises_capture_and_retry_outranks_orphan_details()=runTest {
        for(failure in 1..2) {
            val t=Transport().apply {rejectAt=failure}; val work=work(t)
            assertFailsWith<IllegalStateException> {work.startRecording("audio")}
            assertNull(work.meeting.state.value.recording)
            val failedDetails=work.meeting.state.value.capture?.notice
            t.rejectAt=Int.MAX_VALUE
            val next=work.startRecording("audio")
            if(failedDetails!=null)assertTrue(next.version>failedDetails.version)
            assertEquals(next.id,work.meeting.state.value.recordingCapture()?.id)
            work.close()
        }
    }

    @Test fun authority_and_capture_type_are_checked_before_any_publication()=runTest {
        for(secret in listOf(null,ByteArray(32) {6})) {
            val t=Transport(); val work=work(t,secret)
            assertFailsWith<IllegalStateException> {work.startRecording("audio")}
            assertTrue(t.sent.isEmpty()); work.close()
        }
        val t=Transport(); val work=work(t)
        assertFailsWith<IllegalArgumentException> {work.startRecording("unknown")}
        assertTrue(t.sent.isEmpty()); work.close()
    }

    @Test fun concurrent_starts_have_one_winner_and_stale_stop_cannot_stop_another_recording()=runTest {
        val t=Transport(); val work=work(t)
        val first=async {runCatching {work.startRecording("audio")}}
        val second=async {runCatching {work.startRecording("audio")}}
        val results=listOf(first.await(),second.await())
        assertEquals(1,results.count {it.isSuccess})
        val on=results.first {it.isSuccess}.getOrThrow()
        work.stopRecording("0".repeat(32))
        assertEquals(on,work.meeting.state.value.recording?.notice)
        work.stopRecording(on.id)
        assertFalse(assertNotNull(work.meeting.state.value.recording).notice.on)
        assertNull(work.meeting.state.value.recordingCapture())
        val newer=work.startRecording("audio")
        work.stopRecording(on.id)
        assertEquals(newer,work.meeting.state.value.recording?.notice)
        work.close()
    }

    @Test fun refresh_reposts_both_signed_records_and_stops_after_stop_or_close()=runTest {
        val t=Transport(); val work=work(t)
        work.startRecording("audio")
        runCurrent()
        advanceTimeBy(RECORDING_REPOST_SECONDS*1000+1)
        assertEquals(4,t.sent.size)
        assertEquals(body(t.sent[0]),body(t.sent[2]))
        assertEquals(body(t.sent[1]),body(t.sent[3]))
        work.stopRecording(assertNotNull(work.meeting.state.value.recording).notice.id)
        advanceTimeBy(RECORDING_REPOST_SECONDS*3000)
        assertEquals(5,t.sent.size)
        work.startRecording("audio"); runCurrent(); work.close()
        advanceTimeBy(RECORDING_REPOST_SECONDS*3000)
        assertEquals(7,t.sent.size)
    }

    @Test fun failed_stop_preserves_warning_cancels_running_refresh_and_can_be_retried()=runTest {
        val t=Transport(); val work=work(t)
        val on=work.startRecording("audio"); runCurrent()
        t.rejectAt=3
        assertFailsWith<IllegalStateException> {work.stopRecording(on.id)}
        assertEquals(on,work.meeting.state.value.recording?.notice)
        assertEquals(on.id,work.recordingStopPending.value)
        advanceTimeBy(RECORDING_REPOST_SECONDS*3000)
        assertEquals(3,t.sent.size, "Stopped capture must not refresh its running notice")
        t.rejectAt=Int.MAX_VALUE
        work.stopRecording(on.id)
        val off=assertNotNull(work.meeting.state.value.recording).notice
        assertFalse(off.on)
        assertEquals(on.id,off.id)
        assertTrue(off.version>on.version)
        assertNull(work.recordingStopPending.value)
        val newer=work.startRecording("audio")
        val count=t.sent.size
        work.stopRecording(on.id)
        assertEquals(newer,work.meeting.state.value.recording?.notice)
        assertEquals(count,t.sent.size, "An old retry cannot stop the new recorder")
        work.close()
    }
}
