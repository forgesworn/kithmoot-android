package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
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
        override fun publish(event:NostrEvent) {sent+=event}
        override fun subscribe(filters:List<Filter>)=MutableSharedFlow<NostrEvent>()
        override suspend fun queryStored(filters:List<Filter>,timeoutMs:Long)=emptyList<NostrEvent>()
        override suspend fun publishConfirmed(event:NostrEvent,timeoutMs:Long):Boolean {
            sent+=event
            return sent.size!=rejectAt
        }
    }
    private val storage=object:AssignmentStorage {
        override suspend fun load():String?=null
        override suspend fun save(encrypted:String) {}
    }
    private fun TestScope.work(t:Transport,secret:ByteArray?=sk)=RoomWork(room,key,host,t,storage,
        backgroundScope,now={200},nowMs={200_000},authority=authority,authoritySecretKey=secret)
    private fun body(event:NostrEvent)=assertNotNull(decodeChatEvent(event,room,key,200,channel="control")).body

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
