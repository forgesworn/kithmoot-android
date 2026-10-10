package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/** Reading an origin journal never grants permission to replace it. */
fun interface AssignmentSource {
    suspend fun load(): String?
}
/** Each replacement must be atomic and report failures. Values contain only encrypted records. */
interface AssignmentStorage : AssignmentSource {
    suspend fun save(encrypted: String)
}
data class AssignmentSnapshot(val assignments: List<SharedAssignment> = emptyList(), val ready: Boolean = false,
    val pendingHistory: Int = 0, val pendingSends: Int = 0, val error: String? = null,
    /** Bounded readers never establish complete retained history. */
    val historyComplete: Boolean = false)

/** Durable canonical history and exact retry, independent of the 500-line chat window. */
class AssignmentJournal(
    private val roomId: String,
    private val roomKey: ByteArray,
    private val identity: RoomIdentity?,
    private val transport: RoomTransport,
    private val storage: AssignmentSource,
    private val scope: CoroutineScope,
    private val policy: RoomPolicy? = null,
    private val proof: KindredProof? = null,
    private val now: () -> Long = { System.currentTimeMillis()/1000 },
    initialTrafficRoomId: String = roomId,
    initialTrafficRoomKey: ByteArray = roomKey,
    /** A conference room's end, applied to every assignment envelope this device publishes. */
    private val ends: Long? = null,
    /** Workspace navigation observes an already admitted room without a signer,
     * credential or writable storage. Signed actions stay in the origin. */
    private val readOnly: Boolean = false,
    private val readerParticipant: String? = null,
    private val historyLimit: Int = 128,
    private val historySince: Long? = null,
) {
    init {
        require(roomId.matches(Regex("[0-9a-f]{64}")) && roomKey.size == 32)
        require(historyLimit in 1..512 && (historySince == null || historySince >= 0))
        if (readOnly) {
            require(identity == null && storage !is AssignmentStorage) { "A workspace reader has no signer or writable storage" }
            require(readerParticipant?.matches(Regex("[0-9a-f]{64}")) == true)
        } else require(identity != null && storage is AssignmentStorage) { "An assignment writer requires identity and writable storage" }
    }
    private data class Pending(val inner: NostrEvent, val outer: NostrEvent)
    private val mutex=Mutex()
    private val historyMutex=Mutex()
    private var cacheLoaded=false
    private val events=linkedMapOf<String,NostrEvent>()
    private val outbox=linkedMapOf<String,Pending>()
    private val mutable=MutableStateFlow(AssignmentSnapshot())
    val state: StateFlow<AssignmentSnapshot> = mutable.asStateFlow()
    private var collector: Job?=null
    @Volatile private var readerQuery: Deferred<List<NostrEvent>>?=null
    private var opened=false
    @Volatile private var closed=false
    @Volatile private var trafficRoomId=initialTrafficRoomId
    @Volatile private var trafficRoomKey=initialTrafficRoomKey.copyOf()
    private var loaded=false
    private var failure:String?=null
    private fun refresh() {
        if (readOnly && closed) { mutable.value=AssignmentSnapshot(); return }
        val projected=projectAssignments(events.values.toList(),roomId)
        val ready=loaded&&!closed&&failure==null&&projected.pending.isEmpty()
        mutable.value=AssignmentSnapshot(projected.assignments,ready,projected.pending.size,outbox.size,failure,
            historyComplete=!readOnly&&ready)
    }
    private fun decode(event:NostrEvent):NostrEvent? {
        val id=trafficRoomId;val key=trafficRoomKey
        return decodeChatEvent(event,id,key,now(),policy,ASSIGNMENT_CHANNEL,credentialRoomId=roomId)?.assignment
    }
    private fun live() { check(!closed) { "This assignment room has closed" } }
    private suspend fun persist(nextEvents:Map<String,NostrEvent>,nextOutbox:Map<String,Pending>) {
        check(!readOnly) { "Workspace activity cannot write assignment history" }
        check(nextEvents.size<=20_000&&nextOutbox.size<=100) { "Assignment history is full" }
        val value=buildJsonObject {
            put("v",1)
            put("events",JsonArray(nextEvents.values.map { JsonPrimitive(Nip44.encrypt(it.toCompactJson(),roomKey)) }))
            put("outbox",JsonArray(nextOutbox.values.map { pending -> JsonPrimitive(Nip44.encrypt(buildJsonObject {put("inner",pending.inner.toJson());put("outer",pending.outer.toJson())}.toString(),roomKey)) }))
        }
        (storage as AssignmentStorage).save(value.toString())
    }
    suspend fun open() {
        check(!opened&&!closed) { "Assignment journal already opened or closed" };opened=true
        try {
            mutex.withLock {
                val stored=storage.load()
                live()
                stored?.let { stored ->
                    val cache=Json.parseToJsonElement(stored).jsonObject
                    check(cache["v"]==JsonPrimitive(1)&&cache.keys.all{it in setOf("v","events","outbox")}) { "Invalid assignment cache" }
                    val saved=cache.getValue("events").jsonArray;val pending=cache.getValue("outbox").jsonArray
                    check(saved.size<=20_000&&pending.size<=100) { "Assignment cache exceeds its limit" }
                    for(item in saved) {
                        val event=NostrEvent.fromJson(Json.parseToJsonElement(Nip44.decrypt(item.jsonPrimitive.content,roomKey)))
                        check(assignmentPayload(event,roomId)!=null) { "Assignment cache failed authentication" };events[event.id]=event
                    }
                    for(item in pending) {
                        val value=Json.parseToJsonElement(Nip44.decrypt(item.jsonPrimitive.content,roomKey)).jsonObject
                        val inner=NostrEvent.fromJson(value.getValue("inner"));val outer=NostrEvent.fromJson(value.getValue("outer"))
                        val p=assignmentPayload(inner,roomId)
                        check(p!=null&&inner.pubkey==(identity?.participant ?: readerParticipant)) { "Assignment outbox failed authentication" }
                        // An old/expired envelope is retained for inspection, never silently re-signed.
                        check(outbox.put(p.request,Pending(inner,outer))==null) { "Duplicate assignment outbox request" }
                    }
                }
                refresh()
            }
            cacheLoaded=true
            refreshHistory()
        } catch(error:Exception) {
            mutex.withLock {if(readOnly) {events.clear();outbox.clear()};failure="Assignment history could not be verified";refresh()}
            collector?.cancel();throw error
        }
    }
    /** Reconnect only after the retained cache authenticated; never discard an unreadable journal. */
    suspend fun refreshHistory() = historyMutex.withLock {
        live();check(cacheLoaded) { "Stored assignment history has not authenticated" }
        collector?.cancelAndJoin()
        mutex.withLock {loaded=false;refresh()}
        try {
            val id=trafficRoomId;val key=trafficRoomKey
            val address=deriveChatChannel(id,key,ASSIGNMENT_CHANNEL)
            val filters=listOf(Filter(kinds=listOf(KIND_CHAT),tags=mapOf("#d" to listOf(address.id)),
                limit=if(readOnly) historyLimit else null,
                since=if(readOnly) historySince ?: (now()-86_400).coerceAtLeast(0) else null))
            val replayed=AtomicBoolean(false)
            var historical=0
            collector=scope.launch(start=CoroutineStart.UNDISPATCHED) {
                try {
                    val incoming=if(readOnly) transport.subscribeReplayed(filters) { replayed.set(true) } else transport.subscribe(filters)
                    incoming.collect { outer ->
                    if(readOnly && !replayed.get() && ++historical>historyLimit) return@collect
                    val inner=decode(outer)?:return@collect
                    mutex.withLock {
                        live();if(inner.id !in events) {val candidate=events+ (inner.id to inner);if(!readOnly) persist(candidate,outbox);events[inner.id]=inner;refresh()}
                    }
                }} catch (cancelled:CancellationException) { throw cancelled }
                catch (_:Exception) { mutex.withLock {failure="Assignment history could not be saved or read";refresh()} }
            }
            val history=if(readOnly) {
                val query=scope.async(start=CoroutineStart.LAZY) { transport.queryAvailable(filters).take(historyLimit) }
                readerQuery=query
                if(closed) query.cancel()
                query.start()
                try { query.await() } finally { if(readerQuery===query) readerQuery=null }
            } else transport.queryStored(filters)
            mutex.withLock {
                live();val candidate=LinkedHashMap(events)
                history.mapNotNull(::decode).forEach { candidate[it.id]=it }
                if(!readOnly) persist(candidate,outbox);events.clear();events.putAll(candidate);loaded=true;failure=null;refresh()
            }
        } catch(error:Exception) {
            mutex.withLock{failure="Assignment history could not be verified";refresh()}
            collector?.cancel();throw error
        }
    }
    suspend fun submit(assignment:String?,operation:JsonObject,request:String,expectedHead:String?):SharedAssignment {
        check(!readOnly) { "Workspace activity cannot submit assignment updates" }
        val identity=checkNotNull(identity)
        val pending=mutex.withLock {
            live();check(state.value.ready) { state.value.error?:"Wait for assignment history to load" }
            val signer=(identity as? PrimaryIdentity)?.signer?:error("This device cannot sign assignment updates")
            val existing=events.values.firstOrNull { it.pubkey==signer.pubkey&&assignmentPayload(it,roomId)?.request==request }
            val retry=outbox[request]
            val old=existing?:retry?.inner
            if(old!=null) {
                val payload=assignmentPayload(old,roomId)!!
                check(payload.operation==operation&&(assignment==null||payload.assignment==assignment)&&payload.previous==expectedHead) { "Request ID already belongs to different work or an earlier version" }
                retry?:Pending(old,old)
            } else {
                check(outbox.isEmpty()) { "Resolve the pending assignment send before another update" }
                val id=assignment?:assignmentId(signer.pubkey,request)
                val before=state.value.assignments.find{it.id==id}
                check(assignment==null||before!=null) { "Assignment history is missing" }
                check(before?.head==expectedHead) { "Assignment version changed" }
                val inner=signAssignment(signer,roomId,AssignmentPayload(id,request,before?.head,identity.devicePubkey,operation),now())
                val projection=projectAssignments(events.values.toList()+inner,roomId)
                val next=projection.assignments.find{it.id==id}
                check(next!=null&&next.head==inner.id&&next.status!="conflicted"&&inner.id !in projection.pending) { "This update is not permitted in the current assignment state" }
                val trafficId=trafficRoomId;val trafficKey=trafficRoomKey
                val outer=encodeChatEvent(
                    "Assignment ${operation.assignmentText("op")}",identity.participant,identity.credential,
                    trafficId,trafficKey,identity.deviceSecretKey,now(),proof=proof,channel=ASSIGNMENT_CHANNEL,
                    assignment=inner,credentialRoomId=roomId,roomEnds=ends,
                )
                val value=Pending(inner,outer)
                persist(events,outbox+(request to value));outbox[request]=value;refresh();value
            }
        }
        // Existing acknowledged operations return without any network I/O.
        if(pending.outer.kind==KIND_CHAT) {
            live();check(decode(pending.outer)?.id==pending.inner.id) { "Room access changed; the pending operation cannot be republished" }
            check(transport.publishConfirmed(pending.outer)) { "No relay confirmed this assignment update; the exact operation is retained for retry" }
            mutex.withLock {
                live();val candidate=events+(pending.inner.id to pending.inner);val remaining=outbox-request
                persist(candidate,remaining);events[pending.inner.id]=pending.inner;outbox.remove(request);refresh()
            }
        }
        val id=assignmentPayload(pending.inner,roomId)!!.assignment
        return state.value.assignments.first{it.id==id}
    }
    suspend fun retry() {
        check(!readOnly) { "Workspace activity cannot retry assignment updates" }
        val pending=mutex.withLock { outbox.values.toList() }
        for(value in pending) {val p=assignmentPayload(value.inner,roomId)!!;submit(if(p.operation.assignmentText("op")=="create") null else p.assignment,p.operation,p.request,p.previous)}
    }
    /** Keep the stable assignment history and storage key, but move its encrypted live envelope. */
    suspend fun rekey(id:String,key:ByteArray) {
        require(id.matches(Regex("[0-9a-f]{64}"))&&key.size==32)
        live()
        collector?.cancelAndJoin()
        mutex.withLock {trafficRoomId=id;trafficRoomKey=key.copyOf();loaded=false;refresh()}
        refreshHistory()
    }
    /** Stop disclosure immediately. An interrupted send remains encrypted for explicit reconciliation. */
    fun close() {
        closed=true;collector?.cancel();readerQuery?.cancel()
        mutable.value=if(readOnly) AssignmentSnapshot() else mutable.value.copy(ready=false,historyComplete=false)
        if(readOnly) scope.launch(start=CoroutineStart.UNDISPATCHED) {
            mutex.withLock {events.clear();outbox.clear();refresh()}
        }
    }
}
