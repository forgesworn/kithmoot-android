package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkTest {
    @Test fun verified_room_discovery_updates_actions_without_creating_work()=runTest {
        val room="a".repeat(64);val key=ByteArray(32){9}
        val person=PrimaryIdentity.create(room,1000,100,ByteArray(32){1},ByteArray(32){4})
        val agent=PrimaryIdentity.create(room,1000,100,ByteArray(32){2},ByteArray(32){5})
        val incoming=MutableSharedFlow<NostrEvent>(extraBufferCapacity=32);val sent=mutableListOf<NostrEvent>()
        val transport=object:RoomTransport {
            override fun publish(event:NostrEvent){error("Unconfirmed send")}
            override fun subscribe(filters:List<Filter>)=incoming
            override suspend fun queryStored(filters:List<Filter>,timeoutMs:Long)=emptyList<NostrEvent>()
            override suspend fun publishConfirmed(event:NostrEvent,timeoutMs:Long):Boolean{sent.add(event);return true}
        }
        val storage=object:AssignmentStorage{var value:String?=null;override suspend fun load()=value;override suspend fun save(encrypted:String){value=encrypted}}
        val work=RoomWork(room,key,person,transport,storage,backgroundScope,now={200});work.open()
        assertEquals("{\"op\":\"catalogue?\"}",decodeChatEvent(sent.single(),room,key,200,channel="control")!!.body)
        val action=buildJsonObject{put("id","brief");put("label","Prepare a brief");put("description","Use the supplied objective");put("inputs",JsonArray(emptyList()))}
        suspend fun announce(host:String=agent.participant,actions:JsonArray=JsonArray(listOf(action))) {
            val control=buildJsonObject{put("op","catalogue");put("host",host);put("name","Rowan");put("agents",buildJsonArray{add(buildJsonObject{put("id","oathrun");put("name","Rowan");put("actions",actions)})});put("running",buildJsonArray{add(buildJsonObject{put("id","oathrun");put("participant",agent.participant)})})}
            incoming.emit(encodeChatEvent(control.toString(),agent.participant,agent.credential,room,key,agent.deviceSecretKey,200,channel="control"));runCurrent()
        }
        announce();assertEquals("brief",work.actions.value.single().id);assertEquals(agent.participant,work.actions.value.single().owner)
        announce(host=person.participant,actions=JsonArray(emptyList()));assertEquals(1,work.actions.value.size,"Another host cannot withdraw this agent's catalogue")
        announce(actions=JsonArray(emptyList()));assertTrue(work.actions.value.isEmpty())
        assertTrue(work.journal.state.value.assignments.isEmpty());assertEquals(1,sent.size,"Discovery never publishes an assignment")
        work.close();announce();assertTrue(work.actions.value.isEmpty())
        assertFailsWith<IllegalStateException>{work.refreshActions()}
    }
}
