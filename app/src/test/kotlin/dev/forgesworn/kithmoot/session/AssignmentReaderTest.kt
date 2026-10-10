package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AssignmentReaderTest {
    private val room="a".repeat(64)
    private val key=ByteArray(32){9}
    private val person=PrimaryIdentity.create(room,1000,100,ByteArray(32){1},ByteArray(32){4})
    private class Transport:RoomTransport {
        val incoming=MutableSharedFlow<NostrEvent>(extraBufferCapacity=32)
        var history=listOf<NostrEvent>()
        val requests=mutableListOf<List<Filter>>()
        var subscribed=0; var storedQueries=0; var publications=0
        var replayComplete:(()->Unit)?=null
        var queryGate:CompletableDeferred<Unit>?=null;var queriesStopped=0
        override fun publish(event:NostrEvent) {publications++;error("A workspace reader cannot publish")}
        override suspend fun publishConfirmed(event:NostrEvent,timeoutMs:Long):Boolean {publications++;error("A workspace reader cannot publish")}
        override fun subscribe(filters:List<Filter>)=incoming.also {subscribed++;requests.add(filters)}
        override fun subscribeReplayed(filters:List<Filter>,onReplayComplete:()->Unit)=subscribe(filters).also {replayComplete=onReplayComplete}
        override suspend fun queryStored(filters:List<Filter>,timeoutMs:Long):List<NostrEvent> {storedQueries++;error("Navigation cannot query complete history")}
        override suspend fun queryAvailable(filters:List<Filter>,timeoutMs:Long):List<NostrEvent> {
            requests.add(filters)
            try { queryGate?.await();return history } finally { queriesStopped++ }
        }
    }
    private fun create()=buildJsonObject {put("op","create");put("objective","Inspect private build 41");put("criteria","Return evidence");put("owner",person.participant)}
    private suspend fun creation(request:String="reader_create_0001",target:String=room):NostrEvent =
        signAssignment(person.signer,target,AssignmentPayload(assignmentId(person.participant,request),request,null,person.devicePubkey,create()),200)
    private fun envelope(inner:NostrEvent,id:String=room,trafficKey:ByteArray=key)=encodeChatEvent(
        "Assignment update",person.participant,person.credential,id,trafficKey,person.deviceSecretKey,200,
        channel=ASSIGNMENT_CHANNEL,assignment=inner,credentialRoomId=room)
    private fun cache(events:List<NostrEvent>,outbox:List<Pair<NostrEvent,NostrEvent>> = emptyList())=buildJsonObject {
        put("v",1);put("events",JsonArray(events.map {JsonPrimitive(Nip44.encrypt(it.toCompactJson(),key))}))
        put("outbox",JsonArray(outbox.map { (inner,outer) -> JsonPrimitive(Nip44.encrypt(buildJsonObject {put("inner",inner.toJson());put("outer",outer.toJson())}.toString(),key)) }))
    }.toString()
    @Test fun cachedAndLiveWorkNeedsNeitherSignerNorWritableStorage()=runTest {
        val offered=creation();val network=Transport()
        val source=cache(listOf(offered));var loads=0
        val reader=AssignmentJournal(room,key,null,network,AssignmentSource {loads++;source},backgroundScope,
            now={200},readOnly=true,readerParticipant=person.participant)
        reader.open()
        assertEquals(1,loads);assertEquals(offered.id,reader.state.value.assignments.single().head)
        assertTrue(reader.state.value.ready);assertFalse(reader.state.value.historyComplete)
        val claim=signAssignment(person.signer,room,AssignmentPayload(assignmentId(person.participant,"reader_create_0001"),"reader_claim_0001",offered.id,person.devicePubkey,
            buildJsonObject {put("op","claim");put("executor","reader_executor_01");put("next","Inspect the source")}),200)
        network.replayComplete!!.invoke();network.incoming.emit(envelope(claim));runCurrent()
        assertEquals("running",reader.state.value.assignments.single().status)
        assertFailsWith<IllegalStateException>{reader.submit(null,create(),"reader_create_0002",null)}
        assertFailsWith<IllegalStateException>{reader.retry()}
        assertEquals(0,network.publications);assertEquals(0,network.storedQueries)
        assertTrue(network.requests.all { it.single().limit==128 && it.single().since==0L })
        reader.close();runCurrent();assertTrue(reader.state.value.assignments.isEmpty())
        network.incoming.emit(envelope(offered));runCurrent();assertTrue(reader.state.value.assignments.isEmpty())
    }
    @Test fun uncertainOutboxIsVisibleButNeverRepublished()=runTest {
        val offered=creation();val network=Transport();val saved=cache(emptyList(),listOf(offered to envelope(offered)))
        val reader=AssignmentJournal(room,key,null,network,AssignmentSource {saved},backgroundScope,
            now={200},readOnly=true,readerParticipant=person.participant)
        reader.open();assertEquals(1,reader.state.value.pendingSends)
        assertTrue(reader.state.value.assignments.isEmpty())
        assertFailsWith<IllegalStateException>{reader.retry()};assertEquals(0,network.publications)
        reader.close()
    }
    @Test fun missingParentsNeverBecomeAuthenticatedTasksOrCompleteHistory()=runTest {
        val offered=creation();val network=Transport()
        val claim=signAssignment(person.signer,room,AssignmentPayload(assignmentId(person.participant,"reader_create_0001"),"reader_claim_0001",offered.id,person.devicePubkey,
            buildJsonObject {put("op","claim");put("executor","reader_executor_01");put("next","Inspect")}),200)
        network.history=listOf(envelope(claim))
        val active=AssignmentJournal(room,key,null,network,AssignmentSource {null},backgroundScope,
            now={200},readOnly=true,readerParticipant=person.participant,historySince=0)
        active.open();assertEquals(1,active.state.value.pendingHistory)
        assertFalse(active.state.value.ready);assertFalse(active.state.value.historyComplete)
        assertTrue(active.state.value.assignments.isEmpty());active.close()
    }
    @Test fun dishonestReplayIsCappedAndPostReplayLiveUpdatesStillArrive()=runTest {
        val items=(1..4).map {creation("reader_create_000$it")};val network=Transport()
        network.history=items.map {envelope(it)}
        val active=AssignmentJournal(room,key,null,network,AssignmentSource {null},backgroundScope,
            now={200},readOnly=true,readerParticipant=person.participant,historyLimit=2)
        active.open();assertEquals(2,active.state.value.assignments.size)
        items.forEach {network.incoming.emit(envelope(it))};runCurrent()
        assertEquals(2,active.state.value.assignments.size)
        network.replayComplete!!.invoke();network.incoming.emit(envelope(items.last()));runCurrent()
        assertEquals(3,active.state.value.assignments.size)
        assertTrue(network.requests.all {it.single().limit==2});assertFalse(active.state.value.historyComplete)
        active.close()
    }
    @Test fun closeDuringCacheLoadCannotStartSubscriptionsOrRestoreCards()=runTest {
        val offered=creation();val network=Transport();val gate=CompletableDeferred<String?>()
        val reader=AssignmentJournal(room,key,null,network,AssignmentSource {gate.await()},backgroundScope,
            now={200},readOnly=true,readerParticipant=person.participant)
        val opening=launch {assertFailsWith<IllegalStateException>{reader.open()}}
        runCurrent();reader.close();gate.complete(cache(listOf(offered)));opening.join();runCurrent()
        assertEquals(0,network.subscribed);assertTrue(reader.state.value.assignments.isEmpty())
        assertFailsWith<IllegalStateException>{reader.rekey("b".repeat(64),ByteArray(32){8})}
    }
    @Test fun closingDuringRelayReplayCancelsTheOwnedQuery()=runTest {
        val network=Transport();network.queryGate=CompletableDeferred()
        val reader=AssignmentJournal(room,key,null,network,AssignmentSource {null},backgroundScope,
            now={200},readOnly=true,readerParticipant=person.participant)
        val opening=launch {assertFailsWith<CancellationException>{reader.open()}}
        runCurrent();assertEquals(1,network.subscribed);assertEquals(0,network.queriesStopped)
        reader.close();runCurrent();opening.join()
        assertEquals(1,network.queriesStopped);assertTrue(reader.state.value.assignments.isEmpty())
        assertFalse(reader.state.value.ready)
    }
    @Test fun damagedOrWrongRoomCacheDoesNotLeavePartlyDecodedTasks()=runTest {
        val good=creation();val wrong=creation(target="b".repeat(64));val network=Transport()
        for(saved in listOf(cache(listOf(good,wrong)),cache(listOf(good)).replace("\"v\":1","\"v\":2"))) {
            val reader=AssignmentJournal(room,key,null,network,AssignmentSource {saved},backgroundScope,
                now={200},readOnly=true,readerParticipant=person.participant)
            assertFails{reader.open()};assertTrue(reader.state.value.assignments.isEmpty())
            assertFalse(reader.state.value.ready);assertEquals(0,network.subscribed);reader.close()
        }
    }
    @Test fun expiredCredentialDoesNotInvalidateAnAuthenticatedOriginJournal()=runTest {
        val offered=creation();val network=Transport();network.history=listOf(envelope(creation("reader_create_0002")))
        val reader=AssignmentJournal(room,key,null,network,AssignmentSource {cache(listOf(offered))},backgroundScope,
            now={200_000},readOnly=true,readerParticipant=person.participant)
        reader.open()
        // Retained records authenticate their credential at creation, as the
        // origin does; credential expiry prevents signing, not reading history.
        assertEquals(2,reader.state.value.assignments.size)
        assertTrue(reader.state.value.assignments.any {it.head==offered.id})
        assertTrue(network.requests.all {it.single().since==113_600L});assertFalse(reader.state.value.historyComplete)
        reader.close()
    }
    @Test fun callerAuthorisedRekeyKeepsTheStableJournalAndMovesLiveTraffic()=runTest {
        val offered=creation();val network=Transport()
        val reader=AssignmentJournal(room,key,null,network,AssignmentSource {cache(listOf(offered))},backgroundScope,
            now={200},readOnly=true,readerParticipant=person.participant)
        reader.open()
        val id="b".repeat(64);val movedKey=ByteArray(32){8}
        val next=creation("reader_create_0002")
        network.history=listOf(envelope(next,id,movedKey))
        reader.rekey(id,movedKey)
        assertEquals(2,reader.state.value.assignments.size)
        val filter=network.requests.last().single()
        assertEquals(listOf(deriveChatChannel(id,movedKey,ASSIGNMENT_CHANNEL).id),filter.tags["#d"])
        assertEquals(128,filter.limit);assertFalse(reader.state.value.historyComplete)
        assertEquals(0,network.publications);reader.close()
    }
    @Test fun writableCapabilitiesAndInvalidBoundsCannotEnterAReader()=runTest {
        val network=Transport();val source=AssignmentSource {null}
        for(limit in listOf(0,513)) assertFailsWith<IllegalArgumentException>{AssignmentJournal(room,key,null,network,source,backgroundScope,readOnly=true,readerParticipant=person.participant,historyLimit=limit)}
        assertFailsWith<IllegalArgumentException>{AssignmentJournal(room,key,null,network,source,backgroundScope,readOnly=true,readerParticipant=person.participant,historySince=-1)}
        assertFailsWith<IllegalArgumentException>{AssignmentJournal(room,key,person,network,source,backgroundScope,readOnly=true,readerParticipant=person.participant)}
        val writable=object:AssignmentStorage {override suspend fun load():String?=null;override suspend fun save(encrypted:String)=error("Unexpected save")}
        assertFailsWith<IllegalArgumentException>{AssignmentJournal(room,key,null,network,writable,backgroundScope,readOnly=true,readerParticipant=person.participant)}
    }
}
