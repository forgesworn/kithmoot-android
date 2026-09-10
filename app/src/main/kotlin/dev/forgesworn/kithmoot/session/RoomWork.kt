package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class AvailableAssignmentAction(val owner:String,val agentName:String,val definition:JsonObject) {
    val id get()=definition.assignmentText("id")!!
    val label get()=definition.assignmentText("label")!!
    val description get()=definition.assignmentText("description")!!
}

/** Discovery describes capabilities. The signed assignment and executor enforce authority. */
class RoomWork(
    private val roomId:String,private val roomKey:ByteArray,private val identity:RoomIdentity,
    private val transport:RoomTransport,storage:AssignmentStorage,private val scope:CoroutineScope,
    private val policy:RoomPolicy?=null,private val now:()->Long={System.currentTimeMillis()/1000},
) {
    val journal=AssignmentJournal(roomId,roomKey,identity,transport,storage,scope,policy,now=now)
    private val mutableActions=MutableStateFlow<List<AvailableAssignmentAction>>(emptyList())
    val actions=mutableActions.asStateFlow()
    private val mutableError=MutableStateFlow<String?>(null)
    val error=mutableError.asStateFlow()
    private val catalogues=linkedMapOf<String,Pair<Long,List<AvailableAssignmentAction>>>()
    private var collector:Job?=null
    private val discoveryMutex=Mutex()
    @Volatile private var closed=false
    private fun receive(message:ChatMessage) {
        val control=runCatching{Json.parseToJsonElement(message.body).jsonObject}.getOrNull()?:return
        if(control.assignmentText("op")!="catalogue"||control.assignmentText("host")!=message.participant)return
        val entries=control["agents"] as? JsonArray?:return
        val running=control["running"] as? JsonArray?:return
        if(entries.size>32||running.size>32)return
        val actions=mutableListOf<AvailableAssignmentAction>()
        for(value in entries) {
            val entry=value as? JsonObject?:continue
            val id=entry.assignmentText("id")?:continue
            val definitions=validateAssignmentActions(entry["actions"])?:continue
            val name=DisplayName.sanitise(entry.assignmentText("name"))?:"Agent"
            for(item in running) {
                val run=item as? JsonObject?:continue
                val owner=run.assignmentText("participant")?:continue
                if(run.assignmentText("id")!=id||!owner.matches(Regex("[0-9a-f]{64}")))continue
                actions.addAll(definitions.map{AvailableAssignmentAction(owner,name,it.jsonObject)})
            }
        }
        synchronized(catalogues) {
            if(closed||catalogues[message.participant]?.first?.let{it>message.sentAt}==true)return
            if(message.participant !in catalogues&&catalogues.size>=64)return
            catalogues[message.participant]=message.sentAt to actions
            mutableActions.value=catalogues.values.flatMap{it.second}.distinctBy{it.owner to it.id}.take(128)
        }
    }
    suspend fun open() { journal.open();refreshActions() }
    suspend fun refreshActions() = discoveryMutex.withLock {
        check(!closed) {"This room has closed"}
        if(collector?.isActive!=true) {
        val address=deriveChatChannel(roomId,roomKey,"control")
        val filters=listOf(Filter(kinds=listOf(KIND_CHAT),tags=mapOf("#d" to listOf(address.id))))
        collector=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { transport.subscribe(filters).collect { event -> if(!closed)decodeChatEvent(event,roomId,roomKey,now(),policy,"control")?.let(::receive) } }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){mutableError.value="Agent discovery disconnected. Refresh when the room reconnects."}
        }
        // Stored discovery may be absent; the explicit request also reaches a
        // host that joined after this query. History itself is never a job.
        try {transport.queryStored(filters).sortedWith(compareBy({it.createdAt},{it.id})).forEach {decodeChatEvent(it,roomId,roomKey,now(),policy,"control")?.let(::receive)}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){mutableError.value="Stored agent discovery is unavailable; requesting current actions."}
        }
        check(!closed) {"This room has closed"}
        val request=encodeChatEvent("{\"op\":\"catalogue?\"}",identity.participant,identity.credential,roomId,roomKey,identity.deviceSecretKey,now(),channel="control")
        check(transport.publishConfirmed(request)) {"No relay confirmed the agent discovery request"}
        mutableError.value=null
    }
    fun close() {closed=true;journal.close();collector?.cancel();mutableActions.value=emptyList()}
}
