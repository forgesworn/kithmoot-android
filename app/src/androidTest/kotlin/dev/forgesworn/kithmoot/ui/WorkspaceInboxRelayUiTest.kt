package dev.forgesworn.kithmoot.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

/** Actual view model, encrypted origin vaults, native crypto and loopback sockets.
 * The saved admissions and remote workers here are explicit synthetic fixtures. */
class WorkspaceInboxRelayUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private lateinit var model: RoomViewModel
    private var relay: ProjectTestRelay? = null
    private val human = LocalSigner(ByteArray(32) { 1 })
    @After fun cleanup() {
        if (::model.isInitialized) {
            activity.scenario.onActivity { model.closeWorkspaceActivity(); if (model.stage.value == Stage.ROOM) model.leave() }
            ui.await("fixture room closed") { model.stage.value == Stage.START && !model.start.value.busy }
            activity.scenario.onActivity { model.signOut() }
            ui.await("synthetic account closed") { model.start.value.account == null }
        }
        relay?.close()
    }

    @Test fun canonicalWorkAcrossAdmittedRoomsDoesNotPublishOrReadAnUnopenedRoom() = runBlocking {
        val server = ProjectTestRelay().also { relay = it }
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (model.start.value.account != null) {
            activity.scenario.onActivity { model.signOut() }; ui.await("previous account closed") { model.start.value.account == null }
        }
        app.savedRooms.reset()
        activity.scenario.onActivity { model.onRelaysChanged(server.url); model.installLocalTestAccount(ByteArray(32) { 1 }, true) }
        ui.await("account and project directory") { model.start.value.account?.pubkey == human.pubkey && model.start.value.projects.ready }
        val now = System.currentTimeMillis() / 1000
        val admissions = mutableListOf<SavedRoom>()
        val canonicalHeads = mutableMapOf<String, String>()
        val savedJournals = mutableMapOf<String, String>()
        for (index in 1..4) {
            val secret = ByteArray(32) { (index + 20).toByte() }; val root = deriveRoom(secret)
            val external: ParticipantSigner = object : ParticipantSigner by human {}
            val who = PrimaryIdentity.createWith(external, root.roomId, now + 3600, now)
            val saved = SavedRoom.create(secret, who, encodeJoinUrl("https://kithmoot.example/j/", secret, listOf(server.url)),
                listOf(server.url), "Workspace fixture $index", if (index == 4) 0 else now, null, null)
            app.savedRooms.save(saved); admissions.add(saved)
            val worker = PrimaryIdentity.create(root.roomId, now + 3600, now, ByteArray(32) { if (index <= 2) 4 else 5 })
            val request = "workspace_native_create_0$index"
            val id = assignmentId(human.pubkey, request)
            val create = signAssignment(human, root.roomId, AssignmentPayload(id, request, null, who.devicePubkey,
                buildJsonObject { put("op", "create"); put("objective", "Inspect project $index"); put("criteria", "Verified evidence"); put("owner", worker.participant) }), now)
            val claim = signAssignment(worker.signer, root.roomId, AssignmentPayload(id, "workspace_native_claim_0$index", create.id, worker.devicePubkey,
                buildJsonObject { put("op", "claim"); put("executor", "native_executor_0$index"); put("next", "Inspect the build") }), now)
            val block = signAssignment(worker.signer, root.roomId, AssignmentPayload(id, "workspace_native_block_0$index", claim.id, worker.devicePubkey,
                buildJsonObject { put("op", "block"); put("executor", "native_executor_0$index"); put("question", "Which build for project $index?") }), now)
            canonicalHeads[root.roomId] = block.id
            val cache = buildJsonObject {
                put("v", 1); put("events", JsonArray(listOf(JsonPrimitive(Nip44.encrypt(create.toCompactJson(), root.roomKey)))))
                put("outbox", JsonArray(emptyList()))
            }.toString()
            AssignmentVault(app, root.roomId, human.pubkey).save(cache); savedJournals[root.roomId] = cache
            for (event in listOf(claim, block)) server.publish(encodeChatEvent("Assignment update", worker.participant, worker.credential,
                root.roomId, root.roomKey, worker.deviceSecretKey, now, channel = ASSIGNMENT_CHANNEL, assignment = event))
            val colleague = PrimaryIdentity.create(root.roomId, now + 3600, now, ByteArray(32) { 6 })
            server.publish(encodeChatEvent("Human mention in project $index", colleague.participant, colleague.credential,
                root.roomId, root.roomKey, colleague.deviceSecretKey, now, id = "native_mention_0$index", mentions = listOf(human.pubkey)))
        }
        activity.scenario.onActivity { model.refreshSavedRooms() }
        ui.await("four retained fixture rooms") { !model.start.value.loadingRooms && model.start.value.savedRooms.size == 4 }
        val protectedWrites = server.writes.count { it.kind == KIND_CHAT || it.kind == KIND_ROSTER }
        ui.click("Inbox")
        ui.await("three admitted canonical task heads and recent messages") {
            val rows = model.workspace.value.rooms
            rows.size == 3 && rows.all { row -> row.activity.work.assignments.singleOrNull()?.head == canonicalHeads[row.room] && row.activity.messages.isNotEmpty() }
        }
        assertEquals(admissions.take(3).map { it.id }.toSet(), model.workspace.value.rooms.map { it.room }.toSet())
        assertTrue(model.workspace.value.rooms.all { it.activity.work.assignments.single().status == "blocked" })
        assertEquals(protectedWrites, server.writes.count { it.kind == KIND_CHAT || it.kind == KIND_ROSTER })
        for (saved in admissions) assertEquals(savedJournals[saved.id], AssignmentVault(app, saved.id, human.pubkey).load())
        assertTrue(admissions.all { BackgroundInboxVault(app, it.id, it.participant, it.devicePubkey).inbox.state().readThrough == 0L })
        val unopened = admissions.last(); val unopenedRoot = deriveRoom(unopened.secret)
        val forbidden = setOf(unopened.id, deriveChatChannel(unopened.id, unopenedRoot.roomKey, ASSIGNMENT_CHANNEL).id)
        assertTrue(server.requests.none { request -> request["#d"]?.jsonArray?.any { it.jsonPrimitive.content in forbidden } == true })
        ui.await("actual Inbox rendered") { ui.hasText("6 items need attention") }
        ui.click("Open task in room"); ui.room()
        ui.await("canonical originating task") { model.room.value.work.assignments.singleOrNull()?.head == canonicalHeads[model.room.value.roomId] }
        ui.await("selected task visible") { ui.hasText("Task opened from workspace") }
        val originRoom = model.room.value.roomId
        val origin = admissions.single { it.id == originRoom }; val originRoot = deriveRoom(origin.secret)
        ui.await("origin writer ready") { model.room.value.let { it.work.ready && !it.workBusy && it.work.pendingSends == 0 && !it.secondary && it.movedOn == null } }
        ui.replace("Your answer", "Use build 41", awaitValue = true)
        ui.await("answer text retained", 5_000) { ui.hasText("Use build 41") }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.click("Send answer")
        ui.await("signed answer from the originating room") {
            server.writes.mapNotNull { decodeChatEvent(it, originRoom, originRoot.roomKey, System.currentTimeMillis() / 1000,
                channel = ASSIGNMENT_CHANNEL)?.assignment }.any { event ->
                val payload = assignmentPayload(event, originRoom)
                payload?.operation?.get("op") == JsonPrimitive("answer") && payload.previous == canonicalHeads[originRoom]
            }
        }
        ui.click("Leave room"); ui.home()
        ui.click("Inbox")
        ui.await("workspace returned") { model.workspace.value.rooms.size == 3 }
        activity.scenario.onActivity { model.signOut() }
        ui.await("sign out drops all activity") { model.start.value.account == null && model.workspace.value.rooms.isEmpty() }
        assertEquals(4, app.savedRooms.list().size)
    }
}
