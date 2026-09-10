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

/** Each replacement must be atomic and report failures. Values contain only encrypted records. */
interface AssignmentStorage {
    suspend fun load(): String?
    suspend fun save(encrypted: String)
}
data class AssignmentSnapshot(val assignments: List<SharedAssignment> = emptyList(), val ready: Boolean = false,
    val pendingHistory: Int = 0, val pendingSends: Int = 0, val error: String? = null)

/** Durable canonical history and exact retry, independent of the 500-line chat window. */
class AssignmentJournal(
    private val roomId: String,
    private val roomKey: ByteArray,
    private val identity: RoomIdentity,
    private val transport: RoomTransport,
    private val storage: AssignmentStorage,
    private val scope: CoroutineScope,
    private val policy: RoomPolicy? = null,
    private val proof: KindredProof? = null,
    private val now: () -> Long = { System.currentTimeMillis()/1000 },
) {
    private data class Pending(val inner: NostrEvent, val outer: NostrEvent)
    private val mutex=Mutex()
    private val historyMutex=Mutex()
    private var cacheLoaded=false
    private val events=linkedMapOf<String,NostrEvent>()
    private val outbox=linkedMapOf<String,Pending>()
    private val mutable=MutableStateFlow(AssignmentSnapshot())
    val state: StateFlow<AssignmentSnapshot> = mutable.asStateFlow()
    private var collector: Job?=null
    private var opened=false
    @Volatile private var closed=false
    private var loaded=false
    private var failure:String?=null
    private fun refresh() {
        val projected=projectAssignments(events.values.toList(),roomId)
        mutable.value=AssignmentSnapshot(projected.assignments,loaded&&!closed&&failure==null&&projected.pending.isEmpty(),projected.pending.size,outbox.size,failure)
    }
    private fun decode(event:NostrEvent)=decodeChatEvent(event,roomId,roomKey,now(),policy,ASSIGNMENT_CHANNEL)?.assignment
    private fun live() { check(!closed) { "This assignment room has closed" } }
    private suspend fun persist(nextEvents:Map<String,NostrEvent>,nextOutbox:Map<String,Pending>) {
        check(nextEvents.size<=20_000&&nextOutbox.size<=100) { "Assignment history is full" }
        val value=buildJsonObject {
            put("v",1)
            put("events",JsonArray(nextEvents.values.map { JsonPrimitive(Nip44.encrypt(it.toCompactJson(),roomKey)) }))
            put("outbox",JsonArray(nextOutbox.values.map { pending -> JsonPrimitive(Nip44.encrypt(buildJsonObject {put("inner",pending.inner.toJson());put("outer",pending.outer.toJson())}.toString(),roomKey)) }))
        }
        storage.save(value.toString())
    }
    suspend fun open() {
        check(!opened&&!closed) { "Assignment journal already opened or closed" };opened=true
        try {
            mutex.withLock {
                storage.load()?.let { stored ->
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
                        check(p!=null&&inner.pubkey==identity.participant) { "Assignment outbox failed authentication" }
                        // An old/expired envelope is retained for inspection, never silently re-signed.
                        check(outbox.put(p.request,Pending(inner,outer))==null) { "Duplicate assignment outbox request" }
                    }
                }
                refresh()
            }
            cacheLoaded=true
            refreshHistory()
        } catch(error:Exception) {
            mutex.withLock {failure="Assignment history could not be verified";refresh()}
            collector?.cancel();throw error
        }
    }
    /** Reconnect only after the retained cache authenticated; never discard an unreadable journal. */
    suspend fun refreshHistory() = historyMutex.withLock {
        live();check(cacheLoaded) { "Stored assignment history has not authenticated" }
        collector?.cancelAndJoin()
        mutex.withLock {loaded=false;refresh()}
        try {
            val address=deriveChatChannel(roomId,roomKey,ASSIGNMENT_CHANNEL)
            val filters=listOf(Filter(kinds=listOf(KIND_CHAT),tags=mapOf("#d" to listOf(address.id))))
            collector=scope.launch(start=CoroutineStart.UNDISPATCHED) {
                try { transport.subscribe(filters).collect { outer ->
                    val inner=decode(outer)?:return@collect
                    mutex.withLock {
                        live();if(inner.id !in events) {val candidate=events+ (inner.id to inner);persist(candidate,outbox);events[inner.id]=inner;refresh()}
                    }
                }} catch (cancelled:CancellationException) { throw cancelled }
                catch (_:Exception) { mutex.withLock {failure="Assignment history could not be saved or read";refresh()} }
            }
            val history=transport.queryStored(filters)
            mutex.withLock {
                live();val candidate=LinkedHashMap(events)
                history.mapNotNull(::decode).forEach { candidate[it.id]=it }
                persist(candidate,outbox);events.clear();events.putAll(candidate);loaded=true;failure=null;refresh()
            }
        } catch(error:Exception) {
            mutex.withLock{failure="Assignment history could not be verified";refresh()}
            collector?.cancel();throw error
        }
    }
    suspend fun submit(assignment:String?,operation:JsonObject,request:String,expectedHead:String?):SharedAssignment {
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
                val outer=encodeChatEvent("Assignment ${operation.assignmentText("op")}",identity.participant,identity.credential,roomId,roomKey,identity.deviceSecretKey,now(),proof=proof,channel=ASSIGNMENT_CHANNEL,assignment=inner)
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
        val pending=mutex.withLock { outbox.values.toList() }
        for(value in pending) {val p=assignmentPayload(value.inner,roomId)!!;submit(if(p.operation.assignmentText("op")=="create") null else p.assignment,p.operation,p.request,p.previous)}
    }
    /** Stop disclosure immediately. An interrupted send remains encrypted for explicit reconciliation. */
    fun close() {closed=true;collector?.cancel();mutable.value=mutable.value.copy(ready=false)}
}
