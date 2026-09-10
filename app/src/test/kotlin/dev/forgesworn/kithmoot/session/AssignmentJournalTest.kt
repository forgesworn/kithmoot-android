package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AssignmentJournalTest {
    private val room="a".repeat(64)
    private val key=ByteArray(32){9}
    private fun identity(person:Int=1,device:Int=4)=PrimaryIdentity.create(room,1000,100,ByteArray(32){person.toByte()},ByteArray(32){device.toByte()})
    private class Storage:AssignmentStorage {
        var value:String?=null;var fail=false
        override suspend fun load()=value
        override suspend fun save(encrypted:String) {if(fail)throw IOException("Synthetic disk full");value=encrypted}
    }
    private class Transport:RoomTransport {
        val incoming=MutableSharedFlow<NostrEvent>(extraBufferCapacity=32)
        val sent=mutableListOf<NostrEvent>();var history=listOf<NostrEvent>();var failQuery=false;var uncertain=false
        override fun publish(event:NostrEvent) {error("Durable work must never use fire-and-forget publication")}
        override fun subscribe(filters:List<Filter>)=incoming
        override suspend fun queryStored(filters:List<Filter>,timeoutMs:Long):List<NostrEvent> {if(failQuery)throw IOException("Synthetic incomplete history");return history}
        override suspend fun publishConfirmed(event:NostrEvent,timeoutMs:Long):Boolean {sent.add(event);if(uncertain)throw IOException("Synthetic lost relay acknowledgement");return true}
    }
    private fun create(owner:String)=buildJsonObject {put("op","create");put("objective","Private build 41 task");put("criteria","Return evidence");put("owner",owner)}
    @Test fun persistsBeforePublicationAndRetriesTheExactEnvelopeAfterRestart()=runTest {
        val store=Storage();val network=Transport();val person=identity()
        var log=AssignmentJournal(room,key,person,network,store,backgroundScope,now={200});log.open()
        network.uncertain=true
        assertFailsWith<IOException>{log.submit(null,create(person.participant),"android_request_0001",null)}
        val first=network.sent.single()
        assertEquals(1,log.state.value.pendingSends)
        assertFalse(store.value!!.contains("Private build 41 task"));assertFalse(store.value!!.contains(person.participant))
        log.close()
        log=AssignmentJournal(room,key,person,network,store,backgroundScope,now={200});log.open()
        assertEquals(1,network.sent.size,"Opening a retained outbox must not send it")
        assertEquals(1,log.state.value.pendingSends)
        network.uncertain=false;log.retry()
        assertEquals(first,network.sent.last());assertEquals(0,log.state.value.pendingSends)
        assertEquals("offered",log.state.value.assignments.single().status)
        log.submit(null,create(person.participant),"android_request_0001",null)
        assertEquals(2,network.sent.size,"An acknowledged request is idempotent")
        log.close()
    }
    @Test fun failedStorageOrIncompleteHistoryCannotAdmitPublication()=runTest {
        val store=Storage();val network=Transport();val person=identity()
        val log=AssignmentJournal(room,key,person,network,store,backgroundScope,now={200});log.open();store.fail=true
        assertFailsWith<IOException>{log.submit(null,create(person.participant),"android_request_0001",null)}
        assertTrue(network.sent.isEmpty());log.close()
        store.fail=false;network.failQuery=true
        val second=AssignmentJournal(room,key,person,network,store,backgroundScope,now={200})
        assertFailsWith<IOException>{second.open()};assertFalse(second.state.value.ready)
        assertFailsWith<IllegalStateException>{second.submit(null,create(person.participant),"android_request_0001",null)}
        assertTrue(network.sent.isEmpty())
        network.failQuery=false;second.refreshHistory();assertTrue(second.state.value.ready)
        second.submit(null,create(person.participant),"android_request_0001",null)
        assertEquals(1,network.sent.size);second.close()
    }
    @Test fun incomingQuestionPersistsAndOnlyItsReviewedHeadCanBeAnswered()=runTest {
        val store=Storage();val network=Transport();val person=identity();val worker=identity(2,5)
        val log=AssignmentJournal(room,key,person,network,store,backgroundScope,now={200});log.open()
        val offered=log.submit(null,create(worker.participant),"android_request_0001",null)
        suspend fun workerUpdate(operation:JsonObject,previous:String,request:String):NostrEvent {
            val inner=signAssignment(worker.signer,room,AssignmentPayload(offered.id,request,previous,worker.devicePubkey,operation),200)
            val outer=encodeChatEvent("Assignment update",worker.participant,worker.credential,room,key,worker.deviceSecretKey,200,channel=ASSIGNMENT_CHANNEL,assignment=inner)
            network.incoming.emit(outer);runCurrent();return inner
        }
        val claimed=workerUpdate(buildJsonObject{put("op","claim");put("executor","worker_installation_01");put("next","Inspect build")},offered.head,"worker_claim_0001")
        val blocked=workerUpdate(buildJsonObject{put("op","block");put("executor","worker_installation_01");put("question","Which build?")},claimed.id,"worker_block_0001")
        assertEquals("blocked",log.state.value.assignments.single().status)
        val answer=buildJsonObject{put("op","answer");put("text","Build 41")}
        assertFailsWith<IllegalStateException>{log.submit(offered.id,answer,"android_answer_0001",claimed.id)}
        assertEquals(1,network.sent.size)
        val resumed=log.submit(offered.id,answer,"android_answer_0001",blocked.id)
        assertEquals("running",resumed.status);assertEquals("Build 41",resumed.fields.assignmentText("answer"))
        log.close()
        val reopened=AssignmentJournal(room,key,person,network,store,backgroundScope,now={200});reopened.open()
        assertEquals(resumed,reopened.state.value.assignments.single());assertEquals(2,network.sent.size);reopened.close()
    }
    @Test fun changedRequestAndClosedRoomCannotPublish()=runTest {
        val store=Storage();val network=Transport();val person=identity()
        val log=AssignmentJournal(room,key,person,network,store,backgroundScope,now={200});log.open()
        log.submit(null,create(person.participant),"android_request_0001",null)
        assertFailsWith<IllegalStateException>{log.submit(null,create("b".repeat(64)),"android_request_0001",null)}
        log.close()
        assertFailsWith<IllegalStateException>{log.submit(null,create(person.participant),"android_request_0002",null)}
        assertEquals(1,network.sent.size)
    }
}
