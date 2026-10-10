package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.ROOM_RELAYS_REPOST_SECONDS
import dev.forgesworn.kithmoot.protocol.RoomNameBook
import dev.forgesworn.kithmoot.protocol.RoomNameRecord
import dev.forgesworn.kithmoot.protocol.carryRoomNameOp
import dev.forgesworn.kithmoot.protocol.encodeRoomNameOp
import dev.forgesworn.kithmoot.protocol.renameRoomOp
import dev.forgesworn.kithmoot.protocol.roomNameFromMessage
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.RoomRelaysRecord
import dev.forgesworn.kithmoot.protocol.decodeRoomRelaysOp
import dev.forgesworn.kithmoot.protocol.encodeRoomRelaysOp
import dev.forgesworn.kithmoot.protocol.encodeHandOp
import dev.forgesworn.kithmoot.protocol.encodeMeetingOp
import dev.forgesworn.kithmoot.protocol.MEETING_REPOST_SECONDS
import dev.forgesworn.kithmoot.protocol.MeetingPolicy
import dev.forgesworn.kithmoot.protocol.SignedMeetingPolicy
import dev.forgesworn.kithmoot.protocol.signMeetingPolicy
import dev.forgesworn.kithmoot.protocol.withMeetingMode
import dev.forgesworn.kithmoot.protocol.withSpeaker
import dev.forgesworn.kithmoot.protocol.verifyRoomRelays
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.relay.StoredHistoryUnavailableException
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
    /** A conference room's end: every control and assignment event carries it as its NIP-40 expiration. */
    private val ends:Long?=null,
    /** The epoch [initialTrafficRoomId] belongs to: every rename read is
     *  filed under the epoch whose control log carried it. */
    initialEpoch:Int=0,
    /** The rename this device accepted before and kept with the saved room,
     *  so it counts toward the name, and is posted again, after every copy
     *  in the log has gone. */
    initialRoomName:RoomNameRecord?=null,
    /** When the authority rekeyed the room into an epoch, unix seconds: the
     *  signed `created_at` of that rekey, if this device holds it. A rename
     *  read under an epoch the room has left counts only up to the rekey out
     *  of it, plus the clock-skew grace. See `RoomNameBook`. */
    private val rekeyedAt:(Int)->Long?={null},
    /** The room's shared name changed. Called with the newest rename that
     *  counts, whether read, carried or made here. */
    private val onRoomName:(RoomNameRecord)->Unit={},
    /** A rename that is not a carried copy was read for the first time,
     *  whether or not it won: what the chat shows as "<who> renamed the
     *  room". Once per rename id. */
    private val onRename:(RoomNameRecord)->Unit={},
    /** Milliseconds, for a rename's `at`. */
    private val nowMs:()->Long={System.currentTimeMillis()},
    /** Meeting mode or a recording started or stopped, read as it happened. */
    onMeetingNews:(MeetingNews)->Unit={},
    /** The secret half of [authority], when this device made the room and
     *  so may run it as a meeting. Null everywhere else. */
    private val authoritySecretKey:ByteArray?=null,
) {
    @Volatile private var trafficRoomId=initialTrafficRoomId
    @Volatile private var trafficRoomKey=initialTrafficRoomKey.copyOf()
    /** The room's meeting policy, recording notice and raised hands, all
     *  read from this control channel and believed on [authority]'s
     *  signature, as the relay list is. */
    val meeting=RoomMeeting(roomId,authority,now,onMeetingNews)
    val journal=AssignmentJournal(roomId,roomKey,identity,transport,storage,scope,policy,now=now,
        initialTrafficRoomId=initialTrafficRoomId,initialTrafficRoomKey=initialTrafficRoomKey,ends=ends)
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
    // The room's shared name: every rename read, by the epoch it was read
    // under. Mirrors `followRoomName` in the web client's src/room-name.ts.
    @Volatile private var trafficEpoch=initialEpoch
    private val names=RoomNameBook().apply { initialRoomName?.let { seed(it.name,it.id,it.at) } }
    private val nameMessages=HashSet<String>()
    private val renameIds=HashSet<String>()
    private var shownName:RoomNameRecord?=null
    private var nameCarry:Job?=null
    @Volatile private var nameCarryScheduled=false
    /** The room's shared name now, or null while nobody has renamed it. */
    fun roomName():RoomNameRecord?=synchronized(names){names.current(trafficEpoch,rekeyedAt)}
    private fun receive(message:ChatMessage,epoch:Int) {
        val control=runCatching{Json.parseToJsonElement(message.body).jsonObject}.getOrNull()?:return
        if(control.assignmentText("op")=="relays") { receiveRoomRelays(message);return }
        if(receiveMeeting(message.body,message.participant,message.sentAt))return
        if(control.assignmentText("op")=="name") { roomNameFromMessage(message.body,message.participant,message.sentAt)?.let{ingestName(message.id,it,epoch)};return }
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
                val event=encodeChatEvent(body,identity.participant,identity.credential,trafficRoomId,trafficRoomKey,identity.deviceSecretKey,now(),channel="control",credentialRoomId=roomId,roomEnds=ends)
                transport.publish(event)
            }
        }
    }
    /** File [record], carried by chat message [messageId], under [epoch];
     *  announce a first-seen rename and a changed name. */
    private fun ingestName(messageId:String,record:RoomNameRecord,epoch:Int) {
        val fresh=synchronized(names) {
            if(closed||!nameMessages.add(messageId))return
            names.add(record,epoch)
            record.by!=null&&renameIds.add(record.id)
        }
        if(fresh)runCatching{onRename(record)}
        settleName()
    }
    /** Work the name out again: after a message, or a rekey, which can
     *  discount renames read under the epoch the room left. */
    private fun settleName() {
        val next=synchronized(names) {
            val next=names.current(trafficEpoch,rekeyedAt)
            val shown=shownName
            if(next==null||(shown!=null&&shown.id==next.id&&shown.at==next.at&&shown.name==next.name))return
            shownName=next
            next
        }
        runCatching{onRoomName(next)}
    }
    /**
     * Rename the room for everybody in it: a `name` op on this epoch's
     * control channel, confirmed by a relay. Throws on an empty name, a
     * closed room or no confirmation.
     */
    suspend fun rename(name:String):RoomNameRecord {
        check(!closed) {"This room has closed"}
        val id=trafficRoomId;val key=trafficRoomKey;val epoch=trafficEpoch
        val at=nowMs()
        val op=renameRoomOp(name,at)
        val sentAt=Math.floorDiv(at,1000L)
        val messageId=Entropy.bytes(16).toHex()
        val event=encodeChatEvent(encodeRoomNameOp(op),identity.participant,identity.credential,id,key,identity.deviceSecretKey,sentAt,
            id=messageId,channel="control",credentialRoomId=roomId,roomEnds=ends,sentAtMs=at)
        check(transport.publishConfirmed(event)) {"No relay confirmed the rename"}
        val record=RoomNameRecord(op.name,op.id,op.at,identity.participant,sentAt)
        // Shown now rather than when a relay echoes it back; the echo is the
        // same message id and changes nothing.
        ingestName(messageId,record,epoch)
        return record
    }
    /** A while from now, post the room's name again if this epoch's control
     *  log lacks a recent copy: random, so members do not all post at once,
     *  and a second copy is harmless. */
    private fun scheduleNameCarry(minMs:Long,spreadMs:Long) {
        synchronized(names) {
            nameCarry?.cancel()
            if(closed)return
            nameCarry=scope.launch {
                delay(minMs+(0 until spreadMs).random())
                carryNameIfDue()
            }
        }
    }
    /** Post the current name again, unchanged and marked carried, if this
     *  epoch's control log holds no copy of it newer than
     *  `ROOM_NAME_REPOST_SECONDS`. Returns whether it posted. */
    internal suspend fun carryNameIfDue():Boolean {
        if(closed)return false
        val id=trafficRoomId;val key=trafficRoomKey;val epoch=trafficEpoch
        val sentAt=now()
        val due=synchronized(names){names.carryDue(epoch,sentAt,rekeyedAt)}?:return false
        val messageId=Entropy.bytes(16).toHex()
        val carried=carryRoomNameOp(due)
        val event=encodeChatEvent(encodeRoomNameOp(carried),identity.participant,identity.credential,id,key,identity.deviceSecretKey,sentAt,
            id=messageId,channel="control",credentialRoomId=roomId,roomEnds=ends)
        val confirmed=runCatching{transport.publishConfirmed(event)}.getOrElse{if(it is CancellationException)throw it;false}
        if(confirmed)ingestName(messageId,RoomNameRecord(due.name,due.id,due.at,null,sentAt),epoch)
        return confirmed
    }
    /**
     * Raise or lower this person's hand for everybody in the room: a `hand`
     * op on this epoch's control channel. Shown here at once; throws when no
     * relay confirmed it.
     */
    suspend fun raiseHand(up:Boolean) {
        check(!closed) {"This room has closed"}
        val sentAt=now()
        meeting.hand(identity.participant,up,sentAt)
        val event=encodeChatEvent(encodeHandOp(up),identity.participant,identity.credential,trafficRoomId,trafficRoomKey,identity.deviceSecretKey,sentAt,
            channel="control",credentialRoomId=roomId,roomEnds=ends)
        check(transport.publishConfirmed(event)) {"No relay confirmed your hand"}
    }
    /** Whether this device holds the room's authority key, and so may run
     *  it as a meeting. */
    val moderator:Boolean=authority!=null&&authoritySecretKey?.let{runCatching{Schnorr.publicKeyHex(it)}.getOrNull()}==authority.lowercase()
    @Volatile private var meetingRepost:Job?=null
    private fun receiveMeeting(body:String,participant:String,sentAt:Long):Boolean {
        val before=meeting.state.value.policy
        if(!meeting.receive(body,participant,sentAt))return false
        if(meeting.state.value.policy!=before)scheduleMeetingRepost()
        return true
    }
    /** The policy a change starts from: the room's own, or none at all. */
    private fun stagePolicy():MeetingPolicy=meeting.state.value.meeting?:MeetingPolicy(false,emptyList(),0)
    /**
     * Turn meeting mode on or off for everybody in the room. Whoever runs
     * the meeting is on its stage. Mirrors `setMeetingMode` in the web
     * client's `app/src/main.ts`.
     */
    suspend fun setMeetingMode(on:Boolean) {
        var next=withMeetingMode(stagePolicy(),on,nowMs())
        if(on&&identity.participant.lowercase() !in next.speakers)next=withSpeaker(next,identity.participant,true,next.version)
        publishMeeting(next)
    }
    /** Put [participant] on the stage, or take them off it. A raised hand
     *  comes down with it, as every device reads the new policy. */
    suspend fun setSpeaker(participant:String,speaking:Boolean)=publishMeeting(withSpeaker(stagePolicy(),participant,speaking,nowMs()))
    /** Sign [next] with the authority key, post it on the control channel,
     *  and adopt it here through the same check every other device makes. */
    private suspend fun publishMeeting(next:MeetingPolicy) {
        check(!closed) {"This room has closed"}
        val sk=authoritySecretKey
        check(moderator&&sk!=null) {"Only the person who made this room can run it as a meeting."}
        val body=encodeMeetingOp(SignedMeetingPolicy(next,signMeetingPolicy(roomId,next,sk)))
        val sentAt=now()
        val event=encodeChatEvent(body,identity.participant,identity.credential,trafficRoomId,trafficRoomKey,identity.deviceSecretKey,sentAt,
            channel="control",credentialRoomId=roomId,roomEnds=ends)
        check(transport.publishConfirmed(event)) {"No relay confirmed the meeting change"}
        receiveMeeting(body,identity.participant,sentAt)
    }
    /** On the authority's device, post a policy that is on again every
     *  [MEETING_REPOST_SECONDS], so somebody who arrives hours in still reads
     *  it from the control log. Restarted by every newer policy. */
    private fun scheduleMeetingRepost() {
        if(!moderator)return
        synchronized(this) {
            meetingRepost?.cancel()
            if(closed)return
            meetingRepost=scope.launch {
                while(isActive) {
                    delay(MEETING_REPOST_SECONDS*1000)
                    val signed=meeting.state.value.policy?.takeIf{it.policy.on}?:return@launch
                    if(closed)return@launch
                    runCatching {
                        transport.publish(encodeChatEvent(encodeMeetingOp(signed),identity.participant,identity.credential,trafficRoomId,trafficRoomKey,
                            identity.deviceSecretKey,now(),channel="control",credentialRoomId=roomId,roomEnds=ends))
                    }
                }
            }
        }
    }
    suspend fun open() { journal.open();refreshActions() }
    suspend fun refreshActions() = discoveryMutex.withLock {
        check(!closed) {"This room has closed"}
        val id=trafficRoomId;val key=trafficRoomKey;val epoch=trafficEpoch
        if(collector?.isActive!=true) {
        val address=deriveChatChannel(id,key,"control")
        val filters=listOf(Filter(kinds=listOf(KIND_CHAT),tags=mapOf("#d" to listOf(address.id))))
        collector=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { transport.subscribe(filters).collect { event -> if(!closed)decodeChatEvent(event,id,key,now(),policy,"control",credentialRoomId=roomId)?.let{receive(it,epoch)} } }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){mutableError.value="Agent discovery disconnected. Refresh when the room reconnects."}
        }
        // Stored discovery may be absent; the explicit request also reaches a
        // host that joined after this query. History itself is never a job.
        try {transport.queryAvailable(filters).sortedWith(compareBy({it.createdAt},{it.id})).forEach {decodeChatEvent(it,id,key,now(),policy,"control",credentialRoomId=roomId)?.let{message->receive(message,epoch)}}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){mutableError.value="Stored agent discovery is unavailable; requesting current actions."}
        maybeRepostRoomRelays()
        if(!nameCarryScheduled) { nameCarryScheduled=true;scheduleNameCarry(30_000L,30_000L) }
        }
        check(!closed) {"This room has closed"}
        val request=encodeChatEvent("{\"op\":\"catalogue?\"}",identity.participant,identity.credential,id,key,identity.deviceSecretKey,now(),channel="control",credentialRoomId=roomId,roomEnds=ends)
        check(transport.publishConfirmed(request)) {"No relay confirmed the agent discovery request"}
        mutableError.value=null
    }
    /** Move onto [epoch]'s keys. The room's name is worked out again, since
     *  a rename read under the epoch left may no longer count, and carried
     *  into the new epoch a few seconds later by whichever member gets there
     *  first. */
    suspend fun rekey(id:String,key:ByteArray,epoch:Int=trafficEpoch+1) {
        require(id.matches(Regex("[0-9a-f]{64}"))&&key.size==32)
        check(!closed) {"This room has closed"}
        collector?.cancelAndJoin();collector=null
        trafficRoomId=id;trafficRoomKey=key.copyOf();trafficEpoch=epoch
        settleName()
        scheduleNameCarry(3_000L,12_000L)
        synchronized(catalogues){catalogues.clear();mutableActions.value=emptyList()}
        try { journal.rekey(id,key) }
        catch (_:StoredHistoryUnavailableException) {
            // The journal already moved its traffic key and refused readiness.
            // Mesh cannot prove complete assignment history; this must neither
            // revive work publication nor strand the authenticated chat epoch.
            check(!journal.state.value.ready && !journal.state.value.historyComplete)
            mutableError.value="Shared work is unavailable on this connection. Room chat can continue."
        }
        val address=deriveChatChannel(id,key,"control")
        val filters=listOf(Filter(kinds=listOf(KIND_CHAT),tags=mapOf("#d" to listOf(address.id))))
        collector=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {transport.subscribe(filters).collect {event -> if(!closed)decodeChatEvent(event,id,key,now(),policy,"control",credentialRoomId=roomId)?.let{receive(it,epoch)}}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){mutableError.value="Agent discovery disconnected. Refresh when the room reconnects."}
        }
    }
    fun close() {closed=true;journal.close();collector?.cancel();synchronized(names){nameCarry?.cancel()};synchronized(this){meetingRepost?.cancel()};mutableActions.value=emptyList()}
}
