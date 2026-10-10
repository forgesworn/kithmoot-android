package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomNameOp
import dev.forgesworn.kithmoot.protocol.encodeRoomNameOp
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkHistoryTransitionTest {
    private val room="a".repeat(64)
    private val key=ByteArray(32){9}
    private val nextId="b".repeat(64)
    private val nextKey=ByteArray(32){7}
    private val person=PrimaryIdentity.create(room,1000,100,ByteArray(32){1},ByteArray(32){4})
    private val peer=PrimaryIdentity.create(room,1000,100,ByteArray(32){2},ByteArray(32){5})
    private class Storage:AssignmentStorage {
        var value:String?=null
        override suspend fun load()=value
        override suspend fun save(encrypted:String){value=encrypted}
    }
    private class Transport:RoomTransport {
        val incoming=MutableSharedFlow<NostrEvent>(extraBufferCapacity=32)
        val sent=mutableListOf<NostrEvent>()
        var retained=true
        var uncertain=false
        var queryFailure:Exception?=null
        var availableQueries=0
        override fun publish(event:NostrEvent){error("No unconfirmed publication fallback")}
        override fun subscribe(filters:List<Filter>)=incoming
        override suspend fun queryStored(filters:List<Filter>,timeoutMs:Long):List<NostrEvent> {
            queryFailure?.let{throw it}
            if(!retained)return super<RoomTransport>.queryStored(filters,timeoutMs)
            return emptyList()
        }
        override suspend fun queryAvailable(filters:List<Filter>,timeoutMs:Long):List<NostrEvent> {
            availableQueries++
            return queryStored(filters,timeoutMs)
        }
        override suspend fun publishConfirmed(event:NostrEvent,timeoutMs:Long):Boolean {
            sent+=event
            if(uncertain)throw PublicationUnconfirmedException()
            return true
        }
    }

    @Test fun `unavailable retained history preserves pending work while new epoch controls continue`()=runTest {
        val transport=Transport();val storage=Storage();val names=mutableListOf<String>()
        val work=RoomWork(room,key,person,transport,storage,backgroundScope,now={200},onRoomName={names+=it.name})
        try {
            work.open()
            val operation=buildJsonObject{put("op","create");put("objective","Retained work");put("criteria","Return evidence");put("owner",person.participant)}
            transport.uncertain=true
            assertFailsWith<PublicationUnconfirmedException>{work.journal.submit(null,operation,"android_request_0001",null)}
            val stored=assertNotNull(storage.value)
            val offered=transport.sent.toList()
            val availableQueries=transport.availableQueries
            transport.uncertain=false;transport.retained=false
            work.rekey(nextId,nextKey,1)
            assertEquals(stored,storage.value,"A missing history capability cannot clear or rewrite a retained outbox")
            assertEquals(1,work.journal.state.value.pendingSends)
            assertFalse(work.journal.state.value.ready)
            assertFalse(work.journal.state.value.historyComplete)
            assertNotNull(work.journal.state.value.error)
            assertNotNull(work.error.value)
            assertEquals(availableQueries,transport.availableQueries,"Partial replay cannot replace complete history")
            assertFailsWith<IllegalStateException>{work.journal.retry()}
            assertEquals(offered,transport.sent,"Unavailable work cannot publish its pending envelope")
            val old=encodeChatEvent(encodeRoomNameOp(RoomNameOp("Old epoch","c".repeat(32),199_500)),
                peer.participant,peer.credential,room,key,peer.deviceSecretKey,200,channel="control",credentialRoomId=room)
            val current=encodeChatEvent(encodeRoomNameOp(RoomNameOp("Current epoch","d".repeat(32),199_500)),
                peer.participant,peer.credential,nextId,nextKey,peer.deviceSecretKey,200,channel="control",credentialRoomId=room)
            transport.incoming.emit(old);runCurrent()
            assertTrue(names.isEmpty(),"The old epoch cannot change successor control state")
            transport.incoming.emit(current);runCurrent()
            assertEquals(listOf("Current epoch"),names)
            assertEquals(stored,storage.value)
            assertEquals(1,work.journal.state.value.pendingSends)
            assertFalse(work.journal.state.value.ready)
        } finally {work.close()}
    }

    @Test fun `supported query and unrelated unsupported failures still abort transition`()=runTest {
        for(failure in listOf(IOException("Incomplete supported history"),UnsupportedOperationException("Unrelated implementation failure"))) {
            val transport=Transport();val storage=Storage()
            val work=RoomWork(room,key,person,transport,storage,backgroundScope,now={200})
            try {
                work.open();val before=storage.value
                transport.queryFailure=failure
                assertSame(failure,assertFailsWith<Exception>{work.rekey(nextId,nextKey,1)})
                assertFalse(work.journal.state.value.ready)
                assertFalse(work.journal.state.value.historyComplete)
                assertEquals(before,storage.value)
            } finally {work.close()}
        }
    }
}
