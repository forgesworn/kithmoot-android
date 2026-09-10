package dev.forgesworn.kithmoot.projects

import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.Projects
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.ui.RoomViewModel
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Run the methods in separate instrumentation processes, with force-stop between them. */
class SharedProjectsRestartTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val marker get() = File(app.noBackupFilesDir, "project-restart-fixture.json")
    private var server: ProjectTestRelay? = null
    @After fun closeRelay() { server?.close() }
    private fun model(): RoomViewModel {
        lateinit var value: RoomViewModel
        activity.scenario.onActivity { value = ViewModelProvider(it)[RoomViewModel::class.java] }
        return value
    }

    @Test fun a_prepare_pending() {
        val relay = ProjectTestRelay().also { server = it; it.acknowledge = false }
        val model = model(); ui.home()
        if (app.accounts.load() != null) {
            ui.await("previous account restored") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }; ui.await("previous account closed") { model.start.value.account == null }
        }
        resetProjectTestVault(app, LocalSigner(ByteArray(32) { 6 }))
        activity.scenario.onActivity {
            model.onRelaysChanged(relay.url)
            model.signInWithSecretKey("06".repeat(32))
        }
        ui.await("synthetic account ready") { model.start.value.account?.pubkey == LocalSigner(ByteArray(32) { 6 }).pubkey && model.start.value.projects.ready }
        ui.click("Projects tab"); ui.click("New project"); ui.replace("Project name", "Resume project")
        ui.replace("People's npubs", npubOf(LocalSigner(ByteArray(32) { 7 }).pubkey)); ui.click("Save project")
        ui.await("two unacknowledged encrypted project writes") { relay.writes.size == 2 && model.start.value.projects.pendingSends == 2 }
        assertTrue(relay.writes.all { it.kind == Projects.WRAP_KIND })
        val value = buildJsonObject {
            put("pid", Process.myPid()); put("owner", model.start.value.account!!.pubkey)
            put("project", model.start.value.projects.projects.single().key)
            put("pendingIds", JsonArray(relay.writes.map { it.id }.sorted().map(::JsonPrimitive)))
            put("relay", relay.url)
        }
        marker.writeText(value.toString())
        assertTrue(app.noBackupFilesDir.listFiles()!!.filter { it.extension == "vault" }.all { !it.readBytes().toString(Charsets.UTF_8).contains("Resume project") })
    }

    @Test fun b_recover_exact_pending() {
        assertTrue("Run a_prepare_pending in an earlier process", marker.isFile)
        val expected = Json.parseToJsonElement(marker.readText()).jsonObject
        assertNotEquals(expected.getValue("pid").jsonPrimitive.int, Process.myPid())
        val relay = ProjectTestRelay().also { server = it }
        val model = model(); ui.home()
        ui.await("restored account") { model.start.value.account?.pubkey == expected.getValue("owner").jsonPrimitive.content }
        assertEquals(expected.getValue("relay").jsonPrimitive.content, model.start.value.relays)
        activity.scenario.onActivity { model.onRelaysChanged(relay.url); model.refreshSharedProjects() }
        ui.await("encrypted project and outbox recovered") { model.start.value.projects.ready && model.start.value.projects.pendingSends == 2 }
        assertTrue("Opening the cache must not replay an uncertain send", relay.writes.isEmpty())
        assertEquals(expected.getValue("project").jsonPrimitive.content, model.start.value.projects.projects.single().key)
        ui.click("Projects tab"); ui.click("Retry project updates")
        ui.await("explicit exact retry acknowledged") { model.start.value.projects.pendingSends == 0 && !model.start.value.projectsBusy }
        assertEquals(expected.getValue("pendingIds").jsonArray.map { it.jsonPrimitive.content }, relay.writes.map { it.id }.sorted())
        activity.scenario.onActivity { model.signOut() }
        ui.await("test account signed out") { model.start.value.account == null }
        marker.delete()
    }
}
