package dev.forgesworn.kithmoot.media.recording

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Explicit disposable-emulator driver force-stops between prepare and verify.
 * Uses the application journal's real Keystore wrapping, synthetic identities
 * and a recording-control transport fixture; no camera, mic or public relay. */
class RecordingStopRestartAndroidTest {
    @Test fun signed_stop_survives_process_death_and_reenvelops_on_current_epoch() = runBlocking {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val args=InstrumentationRegistry.getArguments()
        val fixture=args.getString("recordingStopRestartFixture")
        assumeTrue(fixture?.matches(Regex("recording-stop-restart-[0-9a-f]{12}"))==true)
        val stage=args.getString("recordingStopRestartStage")
        require(stage in setOf("prepare","verify","cleanup"))
        val app=ApplicationProvider.getApplicationContext<KithMootApplication>()
        val room=MessageDigest.getInstance("SHA-256").digest(requireNotNull(fixture).toByteArray()).joinToString("") { "%02x".format(it) }
        val key=ByteArray(32) {9};val authoritySecret=ByteArray(32) {3}
        val authority=Schnorr.publicKeyHex(authoritySecret)
        val who=PrimaryIdentity.create(room,1000,100,ByteArray(32) {1},ByteArray(32) {4})
        val marker=File(app.noBackupFilesDir,"$fixture.json")
        if(stage=="cleanup") {
            app.recordingStops.forgetRoom(room)
            check(!marker.exists()||marker.delete())
            return@runBlocking
        }
        val sent=mutableListOf<NostrEvent>()
        val trafficId=if(stage=="prepare") room else "ef".repeat(32)
        val trafficKey=if(stage=="prepare") key else ByteArray(32) {19}
        val transport=object:RoomTransport {
            override fun publish(event:NostrEvent) {sent+=event}
            override fun subscribe(filters:List<Filter>)=MutableSharedFlow<NostrEvent>()
            override suspend fun queryStored(filters:List<Filter>,timeoutMs:Long)=emptyList<NostrEvent>()
            override suspend fun publishConfirmed(event:NostrEvent,timeoutMs:Long):Boolean {sent+=event;return true}
            override suspend fun publishConfirmedGuarded(event:NostrEvent,generation:Long,stillAllowed:()->Boolean,timeoutMs:Long):Boolean {
                if(!stillAllowed())throw PublicationNotOfferedException()
                return publishConfirmed(event,timeoutMs)
            }
        }
        val assignments=object:AssignmentStorage {
            override suspend fun load():String?=null
            override suspend fun save(encrypted:String) {}
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        if(stage=="prepare") app.recordingStops.forgetRoom(room)
        val work=RoomWork(room,key,who,transport,assignments,scope,now={200},nowMs={200_000},
            authority=authority,authoritySecretKey=if(stage=="prepare") authoritySecret else null,
            initialTrafficRoomId=trafficId,initialTrafficRoomKey=trafficKey,
            recordingStops=app.recordingStops)
        try {
            if(stage=="prepare") {
                val on=work.startRecording("audio")
                val off=requireNotNull(app.recordingStops.pending(room,who.devicePubkey,authority))
                assertEquals(on.id,off.notice.id);assertFalse(off.notice.on);assertNull(work.recordingStopPending.value)
                val wrapped=File(app.noBackupFilesDir,"kithmoot.recording-stops.v1.vault")
                    .readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(wrapped.contains(room));assertFalse(wrapped.contains(encodeRecordingOp(off)))
                marker.writeText(buildJsonObject {put("pid",android.os.Process.myPid());put("off",encodeRecordingOp(off))}.toString())
            } else {
                val before=Json.parseToJsonElement(marker.readText()).jsonObject
                assertNotEquals(before.getValue("pid").jsonPrimitive.int,android.os.Process.myPid())
                val off=requireNotNull(decodeRecordingOp(before.getValue("off").jsonPrimitive.content))
                assertEquals(off,app.recordingStops.pending(room,who.devicePubkey,authority))
                assertEquals(off.notice.id,work.recordingStopPending.value)
                assertFalse("Replay requires no retained authority secret",work.moderator)
                work.stopRecording(off.notice.id)
                val packet=sent.single()
                assertTrue(Events.verify(packet))
                assertNull(decodeChatEvent(packet,room,key,200,channel="control",credentialRoomId=room))
                val body=requireNotNull(decodeChatEvent(packet,trafficId,trafficKey,200,channel="control",credentialRoomId=room)).body
                assertEquals(off,decodeRecordingOp(body));assertTrue(verifyRecordingNotice(room,off.notice,off.sig,authority))
                assertNull(work.recordingStopPending.value)
                assertNull(app.recordingStops.pending(room,who.devicePubkey,authority))
                assertFalse(requireNotNull(work.meeting.state.value.recording).notice.on)
            }
        } finally {work.close();scope.cancel()}
    }
}
