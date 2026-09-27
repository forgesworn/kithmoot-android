package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.protocol.ROOM_RELAYS_REPOST_SECONDS
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.RoomRelaysRecord
import dev.forgesworn.kithmoot.protocol.decodeRoomRelaysOp
import dev.forgesworn.kithmoot.protocol.encodeRoomRelaysOp
import dev.forgesworn.kithmoot.protocol.verifyRoomRelays
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
    initialTrafficRoomId:String=roomId,initialTrafficRoomKey:ByteArray=roomKey,
    /** The inviter pinned in this room's link: the only key whose `relays`
     *  op a device believes. Null (a legacy link, or none pinned) simply
     *  means this room never adopts one. */
    private val authority:String?=null,
    /** The newest `relays` record this device already holds for the room,
     *  read from its saved copy at open. Reposted once, if stale, after the
     *  control log has loaded; superseded the moment a newer one arrives. */
    initialRoomRelays:RoomRelaysRecord?=null,
    /** A `relays` record has verified against [authority] and outranks
     *  anything this device held before. The caller adopts it: unions the
     *  relays into the live connection and the saved room, and tells the
     *  room. Never called for a record this device already holds or an
     *  older one. */
    private val onRoomRelays:(RoomRelaysRecord,Long)->Unit={_,_->},
) {
    @Volatile private var trafficRoomId=initialTrafficRoomId
    @Volatile private var trafficRoomKey=initialTrafficRoomKey.copyOf()
    val journal=AssignmentJournal(roomId,roomKey,identity,transport,storage,scope,policy,now=now,
        initialTrafficRoomId=initialTrafficRoomId,initialTrafficRoomKey=initialTrafficRoomKey)
    private val mutableActions=MutableStateFlow<List<AvailableAssignmentAction>>(emptyList())
    val actions=mutableActions.asStateFlow()
    private val mutableError=MutableStateFlow<String?>(null)
    val error=mutableError.asStateFlow()
    private val catalogues=linkedMapOf<String,Pair<Long,List<AvailableAssignmentAction>>>()
    private var collector:Job?=null
    private val discoveryMutex=Mutex()
    @Volatile private var closed=false
    // Room relays: kept in memory only, the way the web client keeps them -
    // a fresh visit starts with no view of how recently the log carried the
    // record, so a copy that scrolled out of a newcomer's history window
    // gets reposted rather than assumed still there.
    @Volatile private var knownRoomRelays:RoomRelaysRecord?=initialRoomRelays
    @Volatile private var roomRelaysSeenAt:Long=0L
    @Volatile private var repostedRoomRelays=false
    private fun receive(message:ChatMessage) {
        val control=runCatching{Json.parseToJsonElement(message.body).jsonObject}.getOrNull()?:return
        if(control.assignmentText("op")=="relays") { receiveRoomRelays(message);return }
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
    /** Any member may repost a `relays` record - the signature, not the
     *  sender, is what a device believes - so this is not gated on
     *  [message]'s participant being the authority at all. */
    private fun receiveRoomRelays(message:ChatMessage) {
        val auth=authority?:return
        val record=decodeRoomRelaysOp(message.body)?:return
        if(!verifyRoomRelays(roomId,record.version,record.relays,record.sig,auth))return
        val known=knownRoomRelays
        if(known!=null&&known.version==record.version) { roomRelaysSeenAt=maxOf(roomRelaysSeenAt,message.sentAt);return }
        if(known!=null&&known.version>=record.version)return
        knownRoomRelays=record
        roomRelaysSeenAt=message.sentAt
        onRoomRelays(record,message.sentAt)
    }
    /** Once, after the control log this session has read settles: if this
     *  device holds a record and the newest copy it saw of it in that log is
     *  more than [ROOM_RELAYS_REPOST_SECONDS] old (including never, which
     *  reads as the whole of it), post the very same record again so it
     *  stays inside the window a newcomer reads. A short random delay keeps
     *  every member who reaches this at once from all reposting together. */
    private fun maybeRepostRoomRelays() {
        if(repostedRoomRelays)return
        repostedRoomRelays=true
        val record=knownRoomRelays?:return
        if(roomRelaysSeenAt>now()-ROOM_RELAYS_REPOST_SECONDS)return
        scope.launch(Dispatchers.IO) {
            delay(30_000L+(0 until 30_000L).random())
            if(closed)return@launch
            runCatching {
                val body=encodeRoomRelaysOp(record)
                val event=encodeChatEvent(body,identity.participant,identity.credential,trafficRoomId,trafficRoomKey,identity.deviceSecretKey,now(),channel="control",credentialRoomId=roomId)
                transport.publish(event)
            }
        }
    }
    suspend fun open() { journal.open();refreshActions() }
    suspend fun refreshActions() = discoveryMutex.withLock {
        check(!closed) {"This room has closed"}
        val id=trafficRoomId;val key=trafficRoomKey
        if(collector?.isActive!=true) {
        val address=deriveChatChannel(id,key,"control")
        val filters=listOf(Filter(kinds=listOf(KIND_CHAT),tags=mapOf("#d" to listOf(address.id))))
        collector=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { transport.subscribe(filters).collect { event -> if(!closed)decodeChatEvent(event,id,key,now(),policy,"control",credentialRoomId=roomId)?.let(::receive) } }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){mutableError.value="Agent discovery disconnected. Refresh when the room reconnects."}
        }
        // Stored discovery may be absent; the explicit request also reaches a
        // host that joined after this query. History itself is never a job.
        try {transport.queryAvailable(filters).sortedWith(compareBy({it.createdAt},{it.id})).forEach {decodeChatEvent(it,id,key,now(),policy,"control",credentialRoomId=roomId)?.let(::receive)}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){mutableError.value="Stored agent discovery is unavailable; requesting current actions."}
        maybeRepostRoomRelays()
        }
        check(!closed) {"This room has closed"}
        val request=encodeChatEvent("{\"op\":\"catalogue?\"}",identity.participant,identity.credential,id,key,identity.deviceSecretKey,now(),channel="control",credentialRoomId=roomId)
        check(transport.publishConfirmed(request)) {"No relay confirmed the agent discovery request"}
        mutableError.value=null
    }
    suspend fun rekey(id:String,key:ByteArray) {
        require(id.matches(Regex("[0-9a-f]{64}"))&&key.size==32)
        check(!closed) {"This room has closed"}
        collector?.cancelAndJoin();collector=null
        trafficRoomId=id;trafficRoomKey=key.copyOf()
        synchronized(catalogues){catalogues.clear();mutableActions.value=emptyList()}
        journal.rekey(id,key)
        val address=deriveChatChannel(id,key,"control")
        val filters=listOf(Filter(kinds=listOf(KIND_CHAT),tags=mapOf("#d" to listOf(address.id))))
        collector=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {transport.subscribe(filters).collect {event -> if(!closed)decodeChatEvent(event,id,key,now(),policy,"control",credentialRoomId=roomId)?.let(::receive)}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){mutableError.value="Agent discovery disconnected. Refresh when the room reconnects."}
        }
    }
    fun close() {closed=true;journal.close();collector?.cancel();mutableActions.value=emptyList()}
}
