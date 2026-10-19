package dev.forgesworn.kithmoot.projects

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Installed app, Compose controls, real sockets, native crypto and Android Keystore. */
class SharedProjectsUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    // Scroll the app's accessible containers. Whole-window swipe fallbacks
    // can pull down Android's notification shade instead of revealing a card.
    private val ui = RecoveryUi(useSwipeFallback = false, freshNodes = true, declineBackgroundBatteryAsk = true)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private lateinit var model: RoomViewModel
    private var scenarioLaunchIntent: android.content.Intent? = null
    private var server: ProjectTestRelay? = null
    private val owner = LocalSigner(ByteArray(32) { 1 })
    private val member = LocalSigner(ByteArray(32) { 2 })
    private val other = LocalSigner(ByteArray(32) { 3 })
    private val agent = LocalSigner(ByteArray(32) { 4 })
    private val secondAgent = LocalSigner(ByteArray(32) { 5 })

    private fun asAccount(key: Int, relay: ProjectTestRelay) {
        if (model.start.value.account != null) {
            activity.scenario.onActivity { model.signOut() }
            ui.await("previous account closed") { model.start.value.account == null }
        }
        activity.scenario.onActivity {
            model.onRelaysChanged(relay.url)
            model.installLocalTestAccount(ByteArray(32) { key.toByte() })
        }
        ui.await("account project history") { model.start.value.account != null && model.start.value.projects.ready }
    }
    private fun confirmed() = ui.await("project publication") { !model.start.value.projectsBusy && model.start.value.projects.pendingSends == 0 }
    private fun project(name: String) = model.start.value.projects.projects.single { it.name == name }
    private fun create(name: String, person: LocalSigner, bot: LocalSigner, room: Boolean = false, roomName: String = "Build room") {
        ui.click("New project"); ui.replace("Project name", name)
        ui.replace("People's npubs", npubOf(person.pubkey)); ui.replace("Agents' npubs", npubOf(bot.pubkey))
        if (room) ui.click("Share $roomName with project")
        ui.click("Save project")
        ui.await("saved $name") { model.start.value.projects.projects.any { it.name == name } }
        confirmed()
    }
    private fun screenshot(name: String) {
        ui.await("project editor closed") { !ui.hasText("Save project") && !ui.hasText("Saving…") }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = app.getExternalFilesDir("ui-proof")!!; directory.mkdirs()
        File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @After fun cleanup() {
        // MainActivity correctly keeps the latest external link as its intent.
        // ActivityScenario filters lifecycle events against its launcher intent;
        // restore that fixture intent only after the actual link journey ends,
        // so its rule can observe and verify Activity destruction during teardown.
        scenarioLaunchIntent?.let { original -> activity.scenario.onActivity { it.intent = original } }
        if (::model.isInitialized) {
            if (model.stage.value == Stage.ROOM) { activity.scenario.onActivity { model.leave() }; ui.home() }
            activity.scenario.onActivity { model.signOut() }
            ui.await("account cleanup") { model.start.value.account == null }
        }
        server?.close()
    }

    @Test fun three_projects_deliberate_joins_real_room_admission_and_withdrawal_recovery() {
        val relay = ProjectTestRelay().also { server = it }
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("previous saved account") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }; ui.await("previous account closed") { model.start.value.account == null }
        }
        resetProjectTestVault(app, owner); resetProjectTestVault(app, member)
        app.savedRooms.reset()
        activity.scenario.onActivity { model.refreshSavedRooms() }
        asAccount(1, relay)
        ui.click("New room"); ui.replace("Room name (optional)", "Build room"); ui.click("Start a room"); ui.room()
        val saved = app.savedRooms.get(app.savedRooms.list().single().id)!!
        assertEquals(owner.pubkey, saved.participant)
        ui.click("Back to chats"); ui.home(); ui.openProjects()
        create("Kithmoot", member, agent, room = true)
        create("Bothy", other, agent)
        create("Research", member, secondAgent)
        assertEquals(3, model.start.value.projects.projects.size)
        val original = project("Kithmoot")
        val originalDefinition = requireNotNull(original.definition)
        assertEquals(saved.id, original.roomChoices().single().room)
        val beforeRename = project("Bothy")
        ui.click("Edit Bothy"); ui.replace("Project name", "Bothy planning"); ui.click("Save project")
        ui.await("renamed project") { model.start.value.projects.projects.any { it.name == "Bothy planning" } }; confirmed()
        assertEquals(beforeRename.authority, project("Bothy planning").authority)
        screenshot("three-shared-projects.png")
        val wraps = relay.writes.filter { it.kind == Projects.WRAP_KIND }
        assertTrue(wraps.isNotEmpty())
        assertFalse(wraps.joinToString { it.content }.contains("Research"))

        // Another signed-in person sees only their two invitations and has to join deliberately.
        asAccount(2, relay)
        assertEquals(setOf("Kithmoot", "Research"), model.start.value.projects.projects.map { it.name }.toSet())
        assertTrue(model.start.value.projects.projects.none { it.joined })
        ui.click("Join Kithmoot"); ui.await("deliberate Kithmoot join") { project("Kithmoot").joined }; confirmed()
        assertFalse(project("Research").joined)
        // The existing owner's room identity is not silently replaced by membership in a project.
        ui.click("Open Build room in Kithmoot")
        ui.await("wrong saved identity refused") { model.start.value.error?.startsWith("This room is saved under another identity") == true }
        assertEquals(owner.pubkey, app.savedRooms.get(saved.id)!!.participant)
        ui.click("Back")
        ui.home()
        ui.await("home ready after the refused admission") { !model.start.value.busy }
        ui.longClick("Build room")
        ui.click("Remove from this phone")
        ui.await("local removal confirmation rendered") { ui.hasText("Remove Build room from this phone?") }
        ui.click("Remove from this phone")
        ui.await("explicit local identity removal") { model.start.value.savedRooms.isEmpty() }
        ui.openProjects(); ui.click("Open Build room in Kithmoot"); ui.room()
        assertEquals(saved.id, model.room.value.roomId)
        assertEquals(member.pubkey, model.room.value.selfParticipant)
        assertFalse(model.room.value.micOn); assertFalse(model.room.value.cameraOn)
        assertEquals(member.pubkey, app.savedRooms.get(saved.id)!!.participant)
        screenshot("project-room-admission.png")
        ui.click("Back to chats"); ui.await("back on the Projects page") { ui.hasText("New project") }

        // A signed directory may name a room id that its invitation does not actually admit.
        val badRoom = ProjectRoomChoice("ab".repeat(32), "Wrong room", saved.joinUrl)
        val at = System.currentTimeMillis() / 1000
        fun ownerSnapshot(revision: Int, parent: String, definition: JsonObject): NostrEvent = runBlocking {
            Projects.sign(owner.pubkey, buildJsonObject {
                put("v", 1); put("op", "snapshot"); put("project", original.reference.project); put("revision", revision)
                put("request", "owner-phone-project-$revision"); put("parents", JsonArray(listOf(JsonPrimitive(parent)))); put("definition", definition)
            }, at, owner::sign)
        }
        val malformed = ownerSnapshot(2, original.heads.single(), JsonObject(originalDefinition + mapOf(
            "authorityRevision" to JsonPrimitive(2), "rooms" to JsonArray(listOf(badRoom.toJson())))))
        relay.publish(Projects.wrap(malformed, member.pubkey, at))
        ui.await("changed room directory") { project("Kithmoot").heads == listOf(malformed.id) }
        val writes = relay.writes.size
        ui.click("Open Wrong room in Kithmoot")
        ui.await("wrong admitted room refused") { model.start.value.error?.startsWith("This invitation opens a different room") == true }
        assertEquals(Stage.START, model.stage.value); assertEquals(writes, relay.writes.size)

        val withdrawn = runBlocking { Projects.sign(owner.pubkey, buildJsonObject {
            put("v", 1); put("op", "withdraw"); put("project", original.reference.project); put("revision", 3)
            put("request", "owner-phone-withdrawal"); put("parents", JsonArray(listOf(JsonPrimitive(malformed.id)))); put("recipient", member.pubkey)
        }, at, owner::sign) }
        relay.publish(Projects.wrap(withdrawn, member.pubkey, at))
        ui.await("observed withdrawal") { model.start.value.projects.projects.any { it.reference == original.reference && it.withdrawn } }
        relay.events.clear()
        asAccount(2, relay)
        val restored = model.start.value.projects.projects.single { it.reference == original.reference }
        assertTrue(restored.withdrawn); assertFalse(restored.joined); assertNull(restored.definition)
        val readdedDefinition = JsonObject(originalDefinition + mapOf("authorityRevision" to JsonPrimitive(4),
            "members" to JsonArray(originalDefinition.getValue("members").jsonArray.map { raw ->
                val m = raw.jsonObject
                if (m["pubkey"] == JsonPrimitive(member.pubkey)) JsonObject(m + ("epoch" to JsonPrimitive(4))) else m
            })))
        val readded = ownerSnapshot(4, withdrawn.id, readdedDefinition)
        relay.publish(Projects.wrap(readded, member.pubkey, at))
        ui.await("new membership invitation") { model.start.value.projects.projects.any { it.name == "Kithmoot" && !it.withdrawn } }
        assertFalse(project("Kithmoot").joined)
        ui.click("Join Kithmoot"); ui.await("renewed deliberate join") { project("Kithmoot").joined }; confirmed()
        screenshot("restored-project-membership.png")
    }

    @Test fun a_room_can_be_added_to_an_existing_empty_project_from_home() {
        val relay = ProjectTestRelay().also { server = it }
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("previous saved account") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }
            ui.await("previous account closed") { model.start.value.account == null }
        }
        resetProjectTestVault(app, owner)
        app.savedRooms.reset()
        activity.scenario.onActivity { model.refreshSavedRooms() }
        asAccount(1, relay)
        ui.openProjects()
        create("Empty project", member, agent)
        val original = project("Empty project")
        assertTrue(original.roomChoices().isEmpty())
        ui.click("Back"); ui.home()
        ui.click("New room"); ui.replace("Room name (optional)", "Build room")
        ui.click("Start a room"); ui.room()
        val saved = app.savedRooms.get(app.savedRooms.list().single().id)!!
        ui.click("Back to chats"); ui.home()
        ui.longClick("Build room"); ui.click("Add to a project")
        ui.click("Add room to Empty project")
        ui.click("Save project")
        ui.await("room added to the existing shared project") { project("Empty project").roomChoices().singleOrNull()?.room == saved.id }
        confirmed()
        val updated = project("Empty project")
        assertEquals(original.reference, updated.reference)
        assertEquals(original.definition!!.getValue("members"), updated.definition!!.getValue("members"))
        assertNull("Shared project assignment must not silently become a local label", app.savedRooms.get(saved.id)!!.project)
        screenshot("project-room-assignment.png")
    }

    private data class InvitationFixture(val relay: ProjectTestRelay, val project: SharedProject, val room: SavedRoom)
    private fun invitationFixture(): InvitationFixture {
        val relay = ProjectTestRelay().also { server = it }
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("previous saved account") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }; ui.await("previous account closed") { model.start.value.account == null }
        }
        resetProjectTestVault(app, owner); resetProjectTestVault(app, member)
        app.savedRooms.reset(); activity.scenario.onActivity { model.refreshSavedRooms() }
        asAccount(1, relay)
        ui.click("New room"); ui.replace("Room name (optional)", "Build room"); ui.click("Start a room"); ui.room()
        val room = app.savedRooms.get(model.room.value.roomId)!!
        ui.click("Back to chats"); ui.home(); ui.openProjects()
        create("Invitation project", member, agent, room = true)
        val original = project("Invitation project")
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open("logo-images-v1.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject.getValue("vectors").jsonArray
            .mapNotNull { LogoImages.read(it.jsonObject["payload"]) }.first()
        var edit: dev.forgesworn.kithmoot.ui.room.RoomLogoEditScope? = null
        activity.scenario.onActivity { edit = model.openProjectLogoEditor(original) }
        runBlocking { requireNotNull(edit).save(image) { true } }
        ui.await("owner invitation artwork") { project("Invitation project").logo?.sha256 == image.sha256 }; confirmed()
        asAccount(2, relay)
        ui.click("Join Invitation project"); ui.await("member joined project") { project("Invitation project").joined }; confirmed()
        val joined = project("Invitation project")
        assertEquals(image.sha256, joined.logo?.sha256)
        app.savedRooms.reset(); activity.scenario.onActivity { model.refreshSavedRooms() }
        ui.click("Back"); ui.home()
        ui.await("cold project room entry") { model.start.value.savedRooms.isEmpty() && !model.start.value.busy }
        return InvitationFixture(relay, joined, room)
    }

    private fun openAsExternalLink(link: String) {
        if (scenarioLaunchIntent == null) activity.scenario.onActivity { scenarioLaunchIntent = android.content.Intent(it.intent) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link),
            context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        ui.await("real project invitation preview") { model.guestAdmission.value?.projectInvitation != null && ui.hasText("Review this invitation") }
    }
    private fun invitationQueries(f: InvitationFixture) = f.relay.requests.count { filter ->
        filter["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == KIND_GROUP_INVITATION } == true &&
            filter["#d"]?.jsonArray?.any { it.jsonPrimitive.content == deriveInvitationId(f.room.invitation!!.invitation) } == true
    }

    @Test fun a_project_link_previews_without_fetching_and_cancelled_lookup_cannot_enter_before_deliberate_retry() {
        val f = invitationFixture(); val queries = invitationQueries(f)
        openAsExternalLink(f.room.joinUrl)
        assertEquals(Stage.START, model.stage.value); assertTrue(app.savedRooms.list().isEmpty())
        assertEquals(queries, invitationQueries(f)); assertEquals(member.pubkey, model.guestAdmission.value!!.projectInvitation!!.account)
        assertTrue(ui.hasText("Invitation project")); assertTrue(ui.hasText(npubOf(member.pubkey)))
        assertTrue("Open room is visible before scrolling through optional device checks", ui.hasText("Open room"))
        assertFalse(ui.hasText("Request to join")); screenshot("project-invitation-real-preview.png")
        f.relay.holdInvitationReplies = true
        ui.click("Open room"); ui.await("held persistent invitation query") { f.relay.heldInvitationQueries > 0 }
        assertEquals(GuestAdmissionPhase.OPENING, model.guestAdmission.value!!.phase)
        ui.click("Cancel opening"); ui.await("cancelled lookup settled") { !model.start.value.busy && model.guestAdmission.value?.phase == GuestAdmissionPhase.CANCELLED }
        f.relay.releaseHeldInvitations()
        assertEquals(Stage.START, model.stage.value); assertTrue(app.savedRooms.list().isEmpty())
        ui.click("Review and retry"); ui.await("persistent preview restored") { model.guestAdmission.value?.phase == GuestAdmissionPhase.PREVIEW }
        assertTrue(app.savedRooms.list().isEmpty())
        ui.click("Open room"); ui.room()
        assertEquals(f.room.id, model.room.value.roomId); assertEquals(member.pubkey, model.room.value.selfParticipant)
        assertEquals(f.project.reference, model.room.value.projectContext)
        assertFalse(model.room.value.onCall); assertFalse(model.room.value.micOn); assertFalse(model.room.value.cameraOn)
        assertEquals(member.pubkey, app.savedRooms.get(f.room.id)!!.participant)
        screenshot("project-invitation-real-admitted-room.png")
    }

    @Test fun changing_account_after_project_review_requires_fresh_review_without_querying_the_invitation() {
        val f = invitationFixture(); val queries = invitationQueries(f)
        openAsExternalLink(f.room.joinUrl)
        activity.scenario.onActivity { model.signOut() }; ui.await("reviewed account signed out") { model.start.value.account == null && !model.start.value.busy }
        ui.click("Open room")
        ui.await("account change refused") { model.guestAdmission.value?.phase == GuestAdmissionPhase.UNAVAILABLE && !model.start.value.busy }
        assertEquals(queries, invitationQueries(f)); assertEquals(Stage.START, model.stage.value)
        assertTrue(app.savedRooms.list().isEmpty())
        ui.click("Review and retry")
        assertEquals(GuestAdmissionPhase.UNAVAILABLE, model.guestAdmission.value!!.phase)
        assertEquals(queries, invitationQueries(f))
        ui.click("Back to rooms"); ui.home()
        asAccount(2, f.relay)
        openAsExternalLink(f.room.joinUrl); ui.click("Open room"); ui.room()
        assertEquals(member.pubkey, model.room.value.selfParticipant)
        assertEquals(f.project.reference, model.room.value.projectContext)
    }

    @Test fun project_withdrawal_during_lookup_prevents_late_room_entry_and_retry() {
        val f = invitationFixture()
        openAsExternalLink(f.room.joinUrl)
        f.relay.holdInvitationReplies = true
        ui.click("Open room")
        ui.await("project invitation lookup held") { f.relay.heldInvitationQueries > 0 }
        val at = System.currentTimeMillis() / 1000
        val withdrawal = runBlocking { Projects.sign(owner.pubkey, buildJsonObject {
            put("v", 1); put("op", "withdraw"); put("project", f.project.reference.project); put("revision", 2)
            put("request", "owner-preview-withdrawal-02"); put("parents", JsonArray(f.project.heads.map(::JsonPrimitive)))
            put("recipient", member.pubkey)
        }, at, owner::sign) }
        f.relay.publish(Projects.wrap(withdrawal, member.pubkey, at))
        ui.await("project access withdrawn during lookup") { model.start.value.projects.projects.any { it.reference == f.project.reference && it.withdrawn } }
        f.relay.releaseHeldInvitations()
        ui.await("late invitation cannot enter withdrawn project") { model.guestAdmission.value?.phase == GuestAdmissionPhase.UNAVAILABLE && !model.start.value.busy }
        assertEquals(Stage.START, model.stage.value); assertTrue(app.savedRooms.list().isEmpty())
        assertEquals("", model.room.value.roomId)
        val queries = invitationQueries(f)
        ui.click("Review and retry")
        assertEquals(GuestAdmissionPhase.UNAVAILABLE, model.guestAdmission.value!!.phase)
        assertEquals(queries, invitationQueries(f)); assertTrue(app.savedRooms.list().isEmpty())
        ui.click("Back to rooms")
    }

    @Test fun a_project_preview_cannot_open_a_different_room_from_a_signed_directory_label() {
        val f = invitationFixture(); val original = f.project
        val wrong = ProjectRoomChoice("ab".repeat(32), "Wrong room", f.room.joinUrl)
        val definition = JsonObject(original.definition!! + mapOf("authorityRevision" to JsonPrimitive(2),
            "rooms" to JsonArray(listOf(wrong.toJson()))))
        val at = System.currentTimeMillis() / 1000
        val snapshot = runBlocking { Projects.sign(owner.pubkey, buildJsonObject {
            put("v", 1); put("op", "snapshot"); put("project", original.reference.project); put("revision", 2)
            put("request", "owner-preview-wrong-room-02"); put("parents", JsonArray(original.heads.map(::JsonPrimitive))); put("definition", definition)
        }, at, owner::sign) }
        f.relay.publish(Projects.wrap(snapshot, member.pubkey, at))
        ui.await("signed directory changed") { project("Invitation project").heads == listOf(snapshot.id) }
        openAsExternalLink(f.room.joinUrl); ui.click("Open room")
        ui.await("authenticated room mismatch refused") { model.guestAdmission.value?.phase == GuestAdmissionPhase.UNAVAILABLE && !model.start.value.busy }
        assertTrue(model.guestAdmission.value!!.detail!!.contains("different room"))
        assertEquals(Stage.START, model.stage.value); assertTrue(app.savedRooms.list().isEmpty())
        assertEquals("", model.room.value.roomId)
        ui.click("Back to rooms")
    }

    @Test fun project_room_logo_override_and_call_origin_survive_browsing_another_project() {
        val relay = ProjectTestRelay().also { server = it }
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("previous saved account") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }; ui.await("previous account closed") { model.start.value.account == null }
        }
        resetProjectTestVault(app, owner); resetProjectTestVault(app, member)
        app.savedRooms.reset(); activity.scenario.onActivity { model.refreshSavedRooms() }
        asAccount(1, relay)
        for (name in listOf("Build room", "Other room")) {
            ui.click("New room"); ui.replace("Room name (optional)", name); ui.click("Start a room"); ui.room()
            ui.click("Back to chats"); ui.home()
        }
        ui.openProjects(); create("Project Alpha", member, agent, room = true)
        create("Project Beta", member, agent, room = true, roomName = "Other room")
        val alpha = project("Project Alpha"); val beta = project("Project Beta")
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open("logo-images-v1.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject.getValue("vectors").jsonArray
            .mapNotNull { LogoImages.read(it.jsonObject["payload"]) }.first()
        var edit: dev.forgesworn.kithmoot.ui.room.RoomLogoEditScope? = null
        activity.scenario.onActivity { edit = model.openProjectLogoEditor(alpha) }
        runBlocking { requireNotNull(edit).save(image) { true } }
        ui.await("project artwork committed") { project("Project Alpha").logo?.sha256 == image.sha256 }; confirmed()
        ui.click("Open Build room in Project Alpha"); ui.room()
        ui.await("project selected in room header") { ui.hasText("Project Alpha") }
        assertEquals(alpha.reference, model.room.value.projectContext); assertNull(model.room.value.logo)
        activity.scenario.onActivity { edit = model.openRoomLogoEditor() }
        runBlocking { requireNotNull(edit).save(image) { true } }
        ui.await("room override committed") { model.room.value.logo?.sha256 == image.sha256 }
        activity.scenario.onActivity { edit = model.openRoomLogoEditor() }
        runBlocking { requireNotNull(edit).save(null) { true } }
        ui.await("room override removed") { model.room.value.logo == null }
        assertEquals(image.sha256, project("Project Alpha").logo?.sha256)
        screenshot("project-room-inherited-logo.png")
        activity.scenario.onActivity { model.joinCall(micOn = false) }
        ui.await("actual call membership captures project") { model.room.value.onCall && model.room.value.callProjectContextCaptured }
        assertEquals(alpha.reference, model.room.value.callProjectContext)
        assertFalse(model.room.value.micOn); assertFalse(model.room.value.cameraOn)
        val callRoom = model.room.value.roomId
        ui.click("Back to chats"); ui.home(); ui.openProjects()
        var visitor: RoomViewModel? = null
        activity.scenario.onActivity { visitor = ViewModelProvider(it).get("chat-only", RoomViewModel::class.java) }
        ui.click("Open Other room in Project Beta")
        ui.await("other project room beside originating call") {
            visitor?.stage?.value == Stage.ROOM && visitor?.room?.value?.roomId == beta.roomChoices().single().room &&
                visitor?.room?.value?.projectContext == beta.reference && ui.hasText("Project Beta") && ui.hasText("Back to the call")
        }
        assertEquals(callRoom, model.room.value.roomId); assertTrue(model.room.value.onCall)
        assertEquals(alpha.reference, model.room.value.callProjectContext)
        assertTrue(ui.hasText("Project Alpha")); assertTrue(ui.hasText("Other room"))
        assertNotEquals(alpha.reference, beta.reference)
        screenshot("project-call-origin-beside-other-room.png")
        ui.click("Back to the call")
        ui.await("returned to originating project room") { ui.hasText("Build room") && !ui.hasText("Back to the call") }
        assertEquals(alpha.reference, model.room.value.callProjectContext)
        activity.scenario.onActivity { model.leaveCall() }
        ui.await("explicit leave clears captured call project") { !model.room.value.onCall && !model.room.value.callProjectContextCaptured }
        assertNull(model.room.value.callProjectContext)
    }

    @Test fun project_logo_owner_scope_private_delivery_restart_and_explicit_removal() {
        val relay = ProjectTestRelay().also { server = it }
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("previous saved account") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }; ui.await("previous account closed") { model.start.value.account == null }
        }
        resetProjectTestVault(app, owner); resetProjectTestVault(app, member)
        asAccount(1, relay); ui.openProjects(); create("Logo workshop", member, agent)
        val original = project("Logo workshop")
        ui.click("Edit logo for Logo workshop"); ui.click("Cancel")
        assertNull(project("Logo workshop").logo)
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open("logo-images-v1.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject.getValue("vectors").jsonArray
            .mapNotNull { LogoImages.read(it.jsonObject["payload"]) }.first()
        var edit: dev.forgesworn.kithmoot.ui.room.RoomLogoEditScope? = null
        activity.scenario.onActivity { edit = model.openProjectLogoEditor(original) }
        runBlocking { requireNotNull(edit).save(image) { true } }
        ui.await("owner logo saved") { project("Logo workshop").logo?.sha256 == image.sha256 }; confirmed()
        assertTrue(requireNotNull(edit).isCurrent())
        val ownerHead = project("Logo workshop").logoHeads
        val wraps = relay.writes.filter { it.tags.any { tag -> tag == listOf("l", ProjectLogos.APP) } }
        assertEquals(3, wraps.size)
        assertFalse(wraps.joinToString { it.content }.contains(image.data))
        screenshot("project-logo-owner.png")

        asAccount(2, relay)
        ui.await("member logo delivered") { project("Logo workshop").logo?.sha256 == image.sha256 }
        assertFalse(project("Logo workshop").joined)
        assertEquals(ownerHead, project("Logo workshop").logoHeads)
        assertFalse(requireNotNull(edit).isCurrent())
        val writes = relay.writes.size
        assertTrue(runCatching { runBlocking { requireNotNull(edit).save(null) { true } } }.isFailure)
        assertEquals(writes, relay.writes.size)
        assertNull(model.openProjectLogoEditor(project("Logo workshop")))
        screenshot("project-logo-member.png")

        // The encrypted owner cache restores artwork even with no relay history.
        relay.events.clear(); asAccount(1, relay)
        assertEquals(image.sha256, project("Logo workshop").logo?.sha256)
        ui.click("Edit logo for Logo workshop"); ui.click("Remove logo for everyone")
        ui.await("removed owner logo") { project("Logo workshop").logo == null && project("Logo workshop").logoVersion == 2 }
        ui.await("accepted removal preview closed") { !ui.hasText("Remove logo for everyone") }; confirmed()
        screenshot("project-logo-removed.png")
        asAccount(2, relay)
        assertNull(project("Logo workshop").logo); assertEquals(2, project("Logo workshop").logoVersion)
    }
}
