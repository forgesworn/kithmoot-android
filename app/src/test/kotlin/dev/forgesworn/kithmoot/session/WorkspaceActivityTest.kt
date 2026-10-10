package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceActivityTest {
    private val secret = ByteArray(32) { 9 }
    private val room = deriveRoom(secret)
    private val person = PrimaryIdentity.create(room.roomId, 1000, 100, ByteArray(32) { 1 }, ByteArray(32) { 4 })
    private val other = PrimaryIdentity.create(room.roomId, 1000, 100, ByteArray(32) { 2 }, ByteArray(32) { 5 })
    private fun message(id: String, author: String = other.participant, body: String = "Please check", at: Long = 200,
        reply: MessageRef? = null, replaces: String? = null, retracts: String? = null, mentions: List<String>? = listOf(person.participant)) =
        ChatMessage(id, author, other.devicePubkey, body, at, reply = reply, replaces = replaces, retracts = retracts, mentions = mentions)
    private val unread = BackgroundInbox.State(0, 190, emptyList(), emptyList())
    private class Transport : RoomTransport {
        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 32)
        var history = emptyList<NostrEvent>()
        var availableGate: CompletableDeferred<Unit>? = null
        val queries = mutableListOf<List<Filter>>()
        var publications = 0; var stored = 0; var cancelled = 0
        override fun publish(event: NostrEvent) { publications++; error("No publication allowed") }
        override fun subscribe(filters: List<Filter>) = incoming
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            stored++; queries.add(filters)
            return history.filter { event -> filters.any { it.kinds?.contains(event.kind) != false } }
        }
        override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            queries.add(filters)
            try { availableGate?.await(); return history }
            catch (e: CancellationException) { cancelled++; throw e }
        }
    }
    private fun envelope(body: String, id: String = "message_01", mentions: List<String>? = listOf(person.participant)) = encodeChatEvent(
        body, other.participant, other.credential, room.roomId, room.roomKey, other.deviceSecretKey, 200, id = id, mentions = mentions)

    @Test fun canonicalTasksAndHumanAttentionStaySeparateFromRoutineAgentProgress() {
        val root = message("root", person.participant, mentions = emptyList())
        val reply = message("reply", reply = refOf(root), mentions = emptyList())
        val nested = message("nested", reply = refOf(message("own_reply", person.participant)), mentions = emptyList())
        val ownReply = message("own_reply", person.participant, reply = refOf(reply), mentions = emptyList())
        val agent = message("agent", "f".repeat(64))
        val self = message("self", person.participant)
        val normal = message("normal", mentions = emptyList())
        val result = workspaceMentions(listOf(root, reply, ownReply, nested, agent, self, normal), person.participant,
            listOf(Named(agent.participant, "Build worker", true)), unread)
        assertEquals(setOf("reply", "nested"), result.map { it.ref.messageId }.toSet())
        assertTrue(result.all { it.reason == "Reply to you" })
    }
    @Test fun authorBoundEditsAndRetractionsCannotBeForgedByAnotherParticipant() {
        val original = message("original", body = "Old text")
        val edit = message("edit", body = "New text", at = 201, replaces = original.id)
        val forgery = message("forgery", "a".repeat(64), "Hide this", at = 202, retracts = original.id)
        val current = workspaceMentions(listOf(original, edit, forgery), person.participant, emptyList(), unread)
        assertEquals("New text", current.single { it.ref == refOf(original) }.text)
        assertTrue(workspaceMentions(listOf(original, edit, message("retraction", retracts = original.id)),
            person.participant, emptyList(), unread).none { it.ref == refOf(original) })
    }
    @Test fun anOfflineDirectoryAgentStaysOutOfHumanAttentionWithoutGrantingAdmission() {
        val progress = message("offline_agent", "f".repeat(64))
        val room = WorkspaceRoomActivity("a".repeat(64), "Workshop", "project", "Project",
            WorkspaceActivitySnapshot(messages = listOf(progress)), directoryPeople = listOf(Named(progress.participant, "Rowan", true)))
        assertTrue(workspaceMentions(room.activity.messages, person.participant, workspacePeople(room), unread).isEmpty())
        assertTrue(room.activity.work.assignments.isEmpty())
        assertEquals("Rowan", workspacePeople(room).single().name)
    }
    @Test fun originReadReceiptsAreAuthorBoundAndNavigationNeverChangesThem() {
        val old = message("old", at = 180)
        val counted = message("counted", at = 188)
        val seen = message("seen")
        val receipt = unread.copy(seen = listOf(refOf(seen).key), unread = listOf(BackgroundInbox.Unread(counted.id, counted.participant, counted.sentAt)))
        assertEquals(listOf("counted"), workspaceMentions(listOf(old, counted, seen), person.participant, emptyList(), receipt).map { it.ref.messageId })
        assertEquals(190, receipt.readThrough)
        assertEquals(listOf(refOf(seen).key), receipt.seen)
    }
    @Test fun decisionsBelongToTheCreatorOrResponsibleOwner() {
        fun task(status: String, creator: String = person.participant, owner: String = other.participant) = SharedAssignment(buildJsonObject {
            put("creator", creator); put("owner", owner); put("status", status); put("question", "Which branch?")
        })
        assertEquals("Which branch?", workspaceDecision(task("blocked"), person.participant))
        assertNotNull(workspaceDecision(task("review"), person.participant))
        assertNull(workspaceDecision(task("running"), person.participant))
        assertNull(workspaceDecision(task("blocked", other.participant), person.participant))
        assertNotNull(workspaceDecision(task("offered", other.participant, person.participant), person.participant))
        assertNotNull(workspaceDecision(task("stopping", other.participant, person.participant), person.participant))
    }
    @Test fun cachedCanonicalWorkAndLiveMentionsNeedNoSignerOrWritableTaskStore() = runTest {
        val create = buildJsonObject { put("op", "create"); put("objective", "Inspect project A"); put("criteria", "Evidence"); put("owner", other.participant) }
        val event = signAssignment(person.signer, room.roomId, AssignmentPayload(assignmentId(person.participant, "workspace_create_01"),
            "workspace_create_01", null, person.devicePubkey, create), 200)
        val cache = buildJsonObject { put("v", 1); put("events", JsonArray(listOf(JsonPrimitive(Nip44.encrypt(event.toCompactJson(), room.roomKey))))); put("outbox", JsonArray(emptyList())) }.toString()
        val network = Transport(); var releases = 0; var loads = 0
        val reader = WorkspaceActivityReader(room.roomId, room.roomKey, person.participant, network,
            AssignmentSource { loads++; cache }, backgroundScope, release = { releases++ }, now = { 200 })
        reader.open(); runCurrent()
        assertEquals(1, loads); assertEquals(event.id, reader.state.value.work.assignments.single().head)
        network.incoming.emit(envelope("Please inspect", "attention_01")); runCurrent()
        assertEquals("attention_01", reader.state.value.messages.single().id)
        assertEquals(1, workspaceMentions(reader.state.value.messages, person.participant, emptyList(), unread).size)
        assertEquals(0, network.publications); assertEquals(0, network.stored)
        assertTrue(network.queries.all { request -> request.all { it.limit == 128 && (it.kinds != listOf(KIND_CHAT) || it.since == 0L) } })
        assertFalse(reader.state.value.work.historyComplete)
        reader.close(); runCurrent()
        assertEquals(1, releases); assertTrue(reader.state.value.messages.isEmpty()); assertTrue(reader.state.value.work.assignments.isEmpty())
        network.incoming.emit(envelope("After close", "attention_02")); runCurrent()
        assertTrue(reader.state.value.messages.isEmpty())
    }
    @Test fun closeCancelsSuspendedQueriesAndLateActivityCannotReturn() = runTest {
        val network = Transport().also { it.availableGate = CompletableDeferred() }
        val reader = WorkspaceActivityReader(room.roomId, room.roomKey, person.participant, network,
            AssignmentSource { null }, backgroundScope, now = { 200 })
        val opening = backgroundScope.launch { reader.open() }
        runCurrent(); assertEquals(2, network.queries.size)
        reader.close(); runCurrent()
        assertTrue(opening.isCancelled); assertEquals(2, network.cancelled)
        network.availableGate!!.complete(Unit); network.incoming.emit(envelope("Private text")); runCurrent()
        assertTrue(reader.state.value.messages.isEmpty()); assertTrue(reader.state.value.work.assignments.isEmpty())
    }
    @Test fun signedEpochChangeDropsOldActivityAndForgedAuthorityCannotCloseIt() = runTest {
        val authority = ByteArray(32) { 6 }; val network = Transport(); var closes = 0
        val reader = WorkspaceActivityReader(room.roomId, room.roomKey, person.participant, network, AssignmentSource { null },
            backgroundScope, authority = Schnorr.publicKeyHex(authority), onClosed = { _, _ -> closes++ }, now = { 200 })
        reader.open(); runCurrent(); network.incoming.emit(envelope("Before closure")); runCurrent()
        val closed = encodeRekeyEvent(room.roomId, authority, deriveEpoch(RoomEpoch(0, secret)), RoomEpoch(1, ByteArray(32) { 7 }),
            emptyList(), emptyList(), 200, closed = true, destruct = true)
        network.incoming.emit(closed.copy(sig = "0".repeat(128))); runCurrent()
        assertEquals(1, reader.state.value.messages.size); assertEquals(0, closes)
        network.incoming.emit(closed); runCurrent()
        assertEquals(1, closes); assertTrue(reader.state.value.messages.isEmpty()); assertNotNull(reader.state.value.error)
        assertEquals(0, network.publications)
    }
    @Test fun accountOrMembershipChangeDropsVerifiedContent() = runTest {
        val network = Transport(); var valid = true
        val reader = WorkspaceActivityReader(room.roomId, room.roomKey, person.participant, network, AssignmentSource { null },
            backgroundScope, valid = { valid }, now = { 200 })
        reader.open(); runCurrent(); network.incoming.emit(envelope("Before sign out")); runCurrent()
        assertEquals(1, reader.state.value.messages.size)
        valid = false; network.incoming.emit(envelope("After sign out", "attention_02")); runCurrent()
        assertTrue(reader.state.value.messages.isEmpty()); assertNotNull(reader.state.value.error)
        assertEquals(0, network.publications)
    }
}
