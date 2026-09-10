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
import dev.forgesworn.kithmoot.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Installed app, Compose controls, real sockets, native crypto and Android Keystore. */
class SharedProjectsUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private lateinit var model: RoomViewModel
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
            model.signInWithSecretKey(key.toString(16).padStart(2, '0').repeat(32))
        }
        ui.await("account project history") { model.start.value.account != null && model.start.value.projects.ready }
    }
    private fun confirmed() = ui.await("project publication") { !model.start.value.projectsBusy && model.start.value.projects.pendingSends == 0 }
    private fun project(name: String) = model.start.value.projects.projects.single { it.name == name }
    private fun create(name: String, person: LocalSigner, bot: LocalSigner, room: Boolean = false) {
        ui.click("New project"); ui.replace("Project name", name)
        ui.replace("People's npubs", npubOf(person.pubkey)); ui.replace("Agents' npubs", npubOf(bot.pubkey))
        if (room) ui.click("Share Build room with project")
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
        ui.click("Chats tab"); ui.replace("Room name (optional)", "Build room"); ui.click("Start a room"); ui.room()
        val saved = app.savedRooms.get(app.savedRooms.list().single().id)!!
        assertEquals(owner.pubkey, saved.participant)
        ui.click("Leave"); ui.home(); ui.click("Projects tab")
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
        ui.click("Chats tab"); ui.click("Forget Build room"); ui.click("Forget room")
        ui.await("explicit local identity removal") { model.start.value.savedRooms.isEmpty() }
        ui.click("Projects tab"); ui.click("Open Build room in Kithmoot"); ui.room()
        assertEquals(saved.id, model.room.value.roomId)
        assertEquals(member.pubkey, model.room.value.selfParticipant)
        assertFalse(model.room.value.micOn); assertFalse(model.room.value.cameraOn)
        assertEquals(member.pubkey, app.savedRooms.get(saved.id)!!.participant)
        screenshot("project-room-admission.png")
        ui.click("Leave"); ui.home()
        assertEquals("projects", model.start.value.homeTab)

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
}
