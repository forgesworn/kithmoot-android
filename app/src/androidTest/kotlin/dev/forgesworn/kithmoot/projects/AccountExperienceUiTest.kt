package dev.forgesworn.kithmoot.projects

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.*
import dev.forgesworn.kithmoot.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Isolated emulator only: synthetic account, loopback relay and real Keystore/Compose. */
class AccountExperienceUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi(useSwipeFallback = false)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private lateinit var model: RoomViewModel
    private val actor = LocalSigner(ByteArray(32) { 41 })
    private lateinit var relay: ProjectTestRelay
    private fun ready() = ui.await("account and room sync ready") {
        model.start.value.account != null && model.start.value.roomBookmarks.ready && model.start.value.projects.ready
    }
    private fun signIn() {
        activity.scenario.onActivity { model.onRelaysChanged(relay.url); model.installLocalTestAccount(ByteArray(32) { 41 }, emulateExternalSigner = true) }
        ready()
    }
    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = app.getExternalFilesDir("account-proof")!!; directory.mkdirs()
        File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Before fun setup() {
        Assume.assumeTrue("Uses disposable emulator storage only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        relay = ProjectTestRelay()
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) {
            ui.await("saved account loaded") { model.start.value.account != null }
            activity.scenario.onActivity { model.signOut() }; ui.await("signed out") { model.start.value.account == null }
        }
        app.savedRooms.reset(); resetProjectTestVault(app, actor)
        EncryptedRoomStorage(app, "kithmoot.bookmarks.${actor.pubkey}", RoomBookmarks.MAX_CACHE_BYTES).reset()
        app.getSharedPreferences("kithmoot.display", 0).edit().remove("relayChoices.${actor.pubkey}").commit()
        activity.scenario.onActivity { model.refreshSavedRooms() }
        relay.publish(runBlocking { actor.sign(0, System.currentTimeMillis()/1000 - 10, emptyList(),
            """{"display_name":"Alex Rowan","about":"Before","custom_client_field":{"keep":true}}""") })
        signIn()
        ui.await("profile avatar identity") { model.start.value.account?.shownName == "Alex Rowan" }
    }
    @After fun cleanup() {
        if (::model.isInitialized) {
            if (model.stage.value == Stage.ROOM) { activity.scenario.onActivity { model.leave() }; ui.await("room closed") { !model.start.value.busy } }
            activity.scenario.onActivity { model.signOut() }; ui.await("account closed") { model.start.value.account == null }
        }
        if (::relay.isInitialized) relay.close()
    }

    @Test fun restoresRoomWithoutDeviceVaultThenOpensSameRoomAndRetainsHistory() {
        ui.click("Chats tab"); ui.replace("Room name (optional)", "Design conversation"); ui.click("Start a room"); ui.room()
        ui.await("bookmark published") { model.start.value.roomBookmarks.rooms.size == 1 && !model.start.value.roomSyncBusy }
        activity.scenario.onActivity { model.sendChat("A message from the first device") }
        ui.await("first device message stored") { model.room.value.chat.any { it.body == "A message from the first device" } }
        val saved = app.savedRooms.get(app.savedRooms.list().single().id)!!
        // A fresh install has no admission and no local bookmark cache, but the account and relay record remain.
        activity.scenario.onActivity { model.leave() }; ui.await("room closed") { !model.start.value.busy }
        activity.scenario.onActivity { model.signOut() }; ui.await("signed out") { model.start.value.account == null }
        app.savedRooms.reset()
        RollbackResistantRoomStorage(app, "kithmoot.epoch.v1", 1024 * 1024).reset()
        EncryptedRoomStorage(app, "kithmoot.bookmarks.${actor.pubkey}", RoomBookmarks.MAX_CACHE_BYTES).reset()
        activity.scenario.onActivity { model.refreshSavedRooms() }; signIn()
        assertTrue(model.start.value.savedRooms.isEmpty())
        assertEquals(saved.id, model.start.value.roomBookmarks.rooms.single().roomId)
        ui.await("restored account rooms rendered") { ui.hasText("From your other devices") && ui.hasDescription("Account menu for Alex Rowan") }
        screenshot("restored-account-room.png")
        assertFalse(ui.hasText("Remove from account"))
        ui.click("Options for Design conversation"); ui.click("Remove Design conversation from account")
        ui.await("account removal confirmation") { ui.hasText("Remove Design conversation from your account?") }
        ui.click("Cancel")
        ui.click("Open Design conversation"); ui.room()
        ui.await("history restored from relay") { model.room.value.chat.any { it.body == "A message from the first device" } }
        assertEquals(saved.id, model.room.value.roomId); assertEquals(actor.pubkey, model.room.value.selfParticipant)
        assertFalse(model.room.value.micOn); assertFalse(model.room.value.cameraOn)
        assertEquals(actor.pubkey, app.savedRooms.get(saved.id)!!.participant)
        screenshot("room-conversation.png")
        // The menu is reachable inside a conversation, without first navigating home.
        ui.click("Account menu for Alex Rowan"); ui.click("Relays"); screenshot("relay-status-in-room.png"); ui.click("Done")
        ui.click("Account menu for Alex Rowan"); ui.click("Sign out")
        ui.await("sign-out confirmation") { ui.hasText("Leave this room and sign out?") }
        ui.click("Sign out")
        ui.await("leave and sign out") { model.stage.value == Stage.START && model.start.value.account == null }
        assertNotNull(app.savedRooms.get(saved.id))
    }

    @Test fun sendingCompletesAfterEchoAndComposerStaysEditableWithoutAcknowledgement() {
        ui.click("Chats tab"); ui.replace("Room name (optional)", "Send regression"); ui.click("Start a room"); ui.room()
        ui.await("bookmark settled") { model.start.value.roomBookmarks.rooms.size == 1 && !model.start.value.roomSyncBusy }
        repeat(8) { index ->
            val body = "Confirmed message $index"
            activity.scenario.onActivity { model.sendChat(body) }
            ui.await("message $index finishes after relay echo") { !model.room.value.chatSending && model.room.value.chat.any { it.body == body } }
            assertNull(model.room.value.chatSendError)
        }
        relay.acknowledge = false
        activity.scenario.onActivity { model.sendChat("Echo without receipt") }
        ui.await("echo while waiting for acknowledgement") { model.room.value.chatSending && model.room.value.chat.any { it.body == "Echo without receipt" } }
        ui.replace("Say something", "My next message")
        ui.await("next draft rendered while send pending") { ui.hasText("My next message") }
        screenshot("composer-awaiting-confirmation.png")
        ui.await("missing acknowledgement releases send control", 90_000) { !model.room.value.chatSending && model.room.value.chatSendError != null }
        assertTrue(ui.hasText("My next message"))
        relay.acknowledge = true
        ui.click("Send")
        ui.await("next message sends after timeout") { !model.room.value.chatSending && model.room.value.chat.any { it.body == "My next message" } }
        activity.scenario.onActivity { model.leave() }; ui.await("room closed") { !model.start.value.busy }
        ui.click("Options for Send regression")
        ui.click("Forget Send regression")
        ui.await("explicit removal confirmation") { ui.hasText("Remove Send regression from this phone?") }
        ui.click("Keep room")
        assertNotNull(app.savedRooms.list().singleOrNull { it.name == "Send regression" })
        screenshot("conversation-list.png")
        ui.click("Open Send regression"); ui.room()
    }

    @Test fun editsPublicProfileFromTopRightAndDisplaysRealRelayStatus() {
        ui.click("Account menu for Alex Rowan"); screenshot("account-menu.png"); ui.click("Edit profile")
        ui.await("profile loaded") { model.start.value.profileMetadata != null && !model.start.value.profileBusy }
        screenshot("profile-editor-top.png")
        ui.replace("Display name", "Alex Updated"); ui.replace("About", "Updated in KithMoot")
        ui.click("Publish profile")
        ui.await("profile accepted") { model.start.value.account?.shownName == "Alex Updated" && !model.start.value.profileBusy }
        val profile = relay.writes.last { it.kind == 0 }
        assertTrue(Events.verify(profile))
        val metadata = Json.parseToJsonElement(profile.content).jsonObject
        assertEquals(JsonPrimitive("Updated in KithMoot"), metadata["about"])
        assertEquals(JsonPrimitive(true), metadata.getValue("custom_client_field").jsonObject["keep"])
        screenshot("profile-editor.png"); ui.click("Done")
        ui.click("Account menu for Alex Updated"); ui.click("Relays")
        ui.await("read receipt visible") { ui.hasText("Read: History read confirmed") }
        screenshot("relay-status.png")
        val eventCount = relay.writes.size
        ui.click("Publish public relay list"); ui.click("Publish relay list")
        ui.await("relay list accepted") { relay.writes.drop(eventCount).any { it.kind == 10002 } && !model.start.value.profileBusy }
        assertEquals(listOf(listOf("r", relay.url.removeSuffix("/"))), relay.writes.last { it.kind == 10002 }.tags)
        ui.click("Done")
    }
}
