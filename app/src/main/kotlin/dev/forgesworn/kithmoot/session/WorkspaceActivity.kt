package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

data class WorkspaceOrigin(val account: String, val room: String, val assignment: String? = null,
    val message: MessageRef? = null, val request: Int = 0)
data class WorkspaceMention(val ref: MessageRef, val text: String, val reason: String, val at: Long)
data class WorkspaceActivitySnapshot(val work: AssignmentSnapshot = AssignmentSnapshot(),
    val messages: List<ChatMessage> = emptyList(), val people: List<Named> = emptyList(), val error: String? = null)
data class WorkspaceRoomActivity(val room: String, val name: String, val project: String?, val projectName: String?,
    val activity: WorkspaceActivitySnapshot = WorkspaceActivitySnapshot(),
    val inbox: BackgroundInbox.State = BackgroundInbox.State(0, 0, emptyList(), emptyList()),
    /** Signed directory labels classify known agents even while offline.
     * They never grant room admission or authority to act. */
    val directoryPeople: List<Named> = emptyList())
data class WorkspaceSnapshot(val account: String? = null, val rooms: List<WorkspaceRoomActivity> = emptyList(),
    val error: String? = null)

fun workspacePeople(room: WorkspaceRoomActivity): List<Named> = (room.activity.people + room.directoryPeople)
    .groupBy { it.participant }.map { (participant, entries) ->
        Named(participant, entries.firstNotNullOfOrNull { it.name }, entries.any { it.agent })
    }

fun workspaceDecision(task: SharedAssignment, participant: String): String? {
    if (task.creator == participant) when (task.status) {
        "blocked" -> return task.question ?: "Answer the question"
        "review" -> return "Review this result"
        "stopped" -> return "Choose the next owner"
        "conflicted" -> return "Reconcile conflicting updates"
    }
    if (task.owner == participant && task.status == "offered") return "Choose whether to start this work"
    if (task.owner == participant && task.status == "stopping") return "Confirm work has stopped"
    return null
}

/** Resolves author-bound edits, retractions and nested replies before selecting
 * human attention. Navigation reads the origin's receipts without marking read. */
fun workspaceMentions(messages: List<ChatMessage>, participant: String, people: List<Named>,
    inbox: BackgroundInbox.State): List<WorkspaceMention> {
    val agents = people.filter { it.agent }.map { it.participant }.toSet()
    val unread = inbox.unread.map { MessageRef(it.id, it.participant).key }.toSet()
    return resolveConversation(messages).byKey.values.mapNotNull { resolved ->
        val original = resolved.original; val shown = resolved.shown; val ref = refOf(original)
        if (resolved.retracted || shown.participant == participant || shown.participant in agents ||
            !shown.isConversation() || shown.assignment != null) return@mapNotNull null
        if (ref.key !in unread && (original.sentAt <= inbox.readThrough || ref.key in inbox.seen)) return@mapNotNull null
        val reply = shown.reply?.participant == participant
        if (!reply && !mentionedBy(shown, participant, people)) return@mapNotNull null
        WorkspaceMention(ref, shown.body, if (reply) "Reply to you" else "Mentioned you", original.sentAt)
    }.sortedWith(compareByDescending<WorkspaceMention> { it.at }.thenBy { it.ref.key })
}

/** Bounded foreground observation with no identity, presence, writable journal,
 * complete chat-history query or publication path. A changed authority epoch
 * drops the view; recovery and all signed decisions stay in the origin room. */
class WorkspaceActivityReader(
    private val roomId: String,
    roomKey: ByteArray,
    private val participant: String,
    private val transport: RoomTransport,
    source: AssignmentSource,
    parent: CoroutineScope,
    private val policy: RoomPolicy? = null,
    initialTrafficRoomId: String = roomId,
    initialTrafficRoomKey: ByteArray = roomKey,
    private val epoch: Int = 0,
    private val authority: String? = null,
    private val valid: () -> Boolean = { true },
    private val onClosed: (NostrEvent, Boolean) -> Unit = { _, _ -> },
    private val release: () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val key = roomKey.copyOf()
    private val trafficKey = initialTrafficRoomKey.copyOf()
    private val trafficId = initialTrafficRoomId
    private val gate = Mutex()
    private val messages = linkedMapOf<String, ChatMessage>()
    private val people = linkedMapOf<String, Named>()
    private val closed = AtomicBoolean(false)
    private val mutable = MutableStateFlow(WorkspaceActivitySnapshot())
    val state: StateFlow<WorkspaceActivitySnapshot> = mutable.asStateFlow()
    private val readerTransport = object : RoomTransport by transport {
        override fun publish(event: NostrEvent): Unit = error("Workspace activity cannot publish")
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean = error("Workspace activity cannot publish")
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long, stillAllowed: () -> Boolean, timeoutMs: Long): Boolean = error("Workspace activity cannot publish")
        override fun publishRecovery(event: NostrEvent): Unit = error("Workspace activity cannot publish")
    }
    private val work = AssignmentJournal(roomId, key, null, readerTransport, source, scope, policy,
        now = now, initialTrafficRoomId = trafficId, initialTrafficRoomKey = trafficKey,
        readOnly = true, readerParticipant = participant)

    private fun emit() {
        if (!closed.get()) mutable.value = WorkspaceActivitySnapshot(work.state.value,
            messages.values.sortedWith(compareMessages), people.values.toList())
    }
    private fun fail(message: String) {
        close()
        mutable.value = WorkspaceActivitySnapshot(error = message)
    }
    private fun authorised(): Boolean {
        if (closed.get()) return false
        if (runCatching(valid).getOrDefault(false)) return true
        fail("Room access changed. Open the room to check its current membership.")
        return false
    }
    fun validate(): Boolean = authorised()
    private fun rekey(event: NostrEvent) {
        val by = authority ?: return
        val next = peekRekeyEpoch(event, roomId, by) ?: return
        if (next <= epoch) return
        val evidence = readRekeyEvidence(event, roomId, by, epoch, trafficKey)
        fail(if (evidence?.closed == true) "This room has ended." else "Room keys changed. Open the room to recover its current activity.")
        if (evidence?.closed == true) onClosed(event, evidence.destruct)
    }
    suspend fun open() = withContext(scope.coroutineContext) { openReader() }
    private suspend fun openReader() {
        check(!closed.get())
        if (!authorised()) return
        try {
            // Complete retained authority history is required for this check.
            // A best-effort activity query never supplies admission evidence.
            authority?.let { by ->
                val filters = listOf(Filter(kinds = listOf(KIND_ROOM_REKEY), authors = listOf(by),
                    tags = mapOf("#d" to listOf(roomId)), limit = 512))
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val replayed = AtomicBoolean(false); var retained = 0
                    try {
                        transport.subscribeReplayed(filters) { replayed.set(true) }.collect {
                            if (replayed.get() || ++retained <= 512) rekey(it)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { fail("Room updates could not be verified. Open the originating room.") }
                }
                val history = transport.queryStored(filters, 8_000)
                if (history.size >= 512) { fail("Open this room to verify its room updates."); return }
                history.sortedBy { it.createdAt }.forEach(::rekey)
            }
            if (!authorised()) return
            scope.launch { work.state.collect { gate.withLock { if (authorised()) emit() } } }
            scope.launch {
                try { work.open() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { fail("Shared work could not be verified. Open the originating room.") }
            }
            val channel = deriveChatChannel(trafficId, trafficKey)
            val filters = listOf(Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(channel.id)),
                since = maxOf(0, now() - 86_400), limit = 128),
                Filter(kinds = listOf(KIND_ROSTER), tags = mapOf("#d" to listOf(trafficId)), limit = 128))
            val replayed = AtomicBoolean(false)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var retained = 0
                try {
                    transport.subscribeReplayed(filters) { replayed.set(true) }.collect { event ->
                        if (replayed.get() || ++retained <= 512) receive(event)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { fail("Activity stopped. Open the originating room to reconnect.") }
            }
            transport.queryAvailable(filters, 8_000).take(512).forEach { receive(it) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail("Activity could not be verified. Open the room to check its work.") }
    }
    private suspend fun receive(event: NostrEvent) = gate.withLock {
        if (!authorised()) return@withLock
        if (event.kind == KIND_ROSTER) {
            decodeRosterEvent(event, trafficId, trafficKey, now(), credentialRoomId = roomId)?.let { entry ->
                val old = people[entry.participant]
                // Keep an authenticated agent declaration for this observation,
                // including after its presence expires or another device appears.
                people[entry.participant] = Named(entry.participant, entry.name, entry.agent || old?.agent == true)
                if (people.size > 512) people.remove(people.keys.first())
            }
        } else if (event.kind == KIND_CHAT) {
            val message = decodeChatEvent(event, trafficId, trafficKey, now(), policy, credentialRoomId = roomId) ?: return@withLock
            messages[refOf(message).key] = message
            if (messages.size > 512) messages.remove(messages.keys.first())
        }
        emit()
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        work.close(); job.cancel(); release()
        mutable.value = WorkspaceActivitySnapshot()
        scope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            gate.withLock { messages.clear(); people.clear() }
        }
        // The reader's private key copies never become Compose state or storage.
        key.fill(0); trafficKey.fill(0)
    }
}
