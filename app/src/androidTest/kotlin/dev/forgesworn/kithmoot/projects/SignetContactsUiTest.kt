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
import dev.forgesworn.signet.contacts.wire.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Installed Compose + real encrypted grants, sockets, private-room admission
 * and history. Uses synthetic already-confirmed contact grants; real Signet
 * approval and hardware signer acceptance remain a separate manual gate. */
class SignetContactsUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi(useSwipeFallback = false, freshNodes = true, declineBackgroundBatteryAsk = true)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private lateinit var model: RoomViewModel
    private lateinit var relay: ProjectTestRelay
    private val alice = LocalSigner(ByteArray(32) { 61 })
    private val bob = LocalSigner(ByteArray(32) { 62 })
    private val now get() = System.currentTimeMillis() / 1000
    private fun resetContactVault(actor: ParticipantSigner, purpose: String) = EncryptedRoomStorage(app, "kithmoot.contacts.$purpose.${actor.pubkey}", 4 * 1024 * 1024).reset()
    private fun seedGrant(actor: ParticipantSigner, peer: ParticipantSigner, name: String) = runBlocking {
        val scopes = listOf(Capability.READ_DIRECTORY, Capability.BLOCKS_READ)
        val pairing = PairingV2("a".repeat(32), "b".repeat(64), "c".repeat(32), "d".repeat(32), relay.url, scopes, 3600, now)
        val projection = ContactProjectionV2(pairing.grantId, scopes, ProjectionFrontier(1, 1, now, "e".repeat(32)), now, now + 3600,
            listOf(ProjectedContact("f".repeat(32), identities = listOf(ProjectedIdentity(peer.pubkey)), displayName = name)))
        val state = ContactsState(pairing.grantId, projection, now, emptyList(), false)
        val store = ContactAccountVault(app, actor.pubkey, "directory")
        store.acquire()
        try { store.save(buildJsonObject {
            put("v", 1); put("account", actor.pubkey); put("pairing", pairing.toJson().stringify())
            put("values", buildJsonObject { put("signet-contacts:state:${pairing.grantId}", state.toJson().stringify()) })
        }.toString()) } finally { store.release() }
    }
    private fun signIn(seed: Byte) {
        activity.scenario.onActivity { model.onRelaysChanged(relay.url); model.installLocalTestAccount(ByteArray(32) { seed }, emulateExternalSigner = true) }
        ui.await("account, directory and private inbox ready", 60_000) {
            model.start.value.account != null && model.start.value.signetContacts.status == "ready" && model.start.value.contactInbox.ready && model.start.value.roomBookmarks.ready
        }
    }
    private fun signOut() {
        if (model.stage.value == Stage.ROOM) { activity.scenario.onActivity { model.leave() }; ui.await("room closed") { !model.start.value.busy } }
        activity.scenario.onActivity { model.signOut() }; ui.await("account signed out") { model.start.value.account == null && !model.start.value.signingIn }
    }
    private fun contacts() { ui.click("People"); ui.await("People rendered") { ui.hasText("Contacts") } }
    /** People › the contact › Message (design-calm-shell 6.1: the actions show under the selected row). */
    private fun message(name: String) { contacts(); ui.click(name); ui.click("Message") }
    private fun screenshot(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.waitForIdle(500, 5_000)
        val bitmap = inst.uiAutomation.takeScreenshot()
        val folder = app.getExternalFilesDir("contact-proof")!!; folder.mkdirs()
        File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Before fun setup() {
        Assume.assumeTrue("Disposable emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        relay = ProjectTestRelay()
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home()
        if (app.accounts.load() != null) { ui.await("saved account restored") { model.start.value.account != null }; signOut() }
        app.savedRooms.reset()
        for (actor in listOf(alice, bob)) {
            resetProjectTestVault(app, actor); resetContactVault(actor, "directory"); resetContactVault(actor, "inbox")
            EncryptedRoomStorage(app, "kithmoot.bookmarks.${actor.pubkey}", RoomBookmarks.MAX_CACHE_BYTES).reset()
            app.getSharedPreferences("kithmoot.display", 0).edit().remove("relayChoices.${actor.pubkey}").commit()
            relay.publish(runBlocking { actor.sign(10050, now, listOf(listOf("relay", relay.url)), "") })
        }
        seedGrant(alice, bob, "Bob Contact"); seedGrant(bob, alice, "Alice Contact")
        activity.scenario.onActivity { model.refreshSavedRooms() }
    }
    @After fun cleanup() { if (::model.isInitialized && model.start.value.account != null) signOut(); if (::relay.isInitialized) relay.close() }

    @Test fun contactCreatesOnePrivateConversationAndRecipientOpensItWithSenderOffline() {
        signIn(61); assertTrue(model.start.value.savedRooms.isEmpty())
        message("Bob Contact"); ui.room()
        val room = model.room.value.roomId
        ui.await("private invitation deliveries acknowledged") { model.start.value.contactInbox.pending == 0 && relay.writes.count { it.kind == 1059 } >= 2 }
        assertEquals(alice.pubkey, model.room.value.selfParticipant)
        assertFalse(model.room.value.micOn); assertFalse(model.room.value.cameraOn)
        activity.scenario.onActivity { model.sendChat("Hello from Alice's contact list") }
        ui.await("first message saved") { model.room.value.chat.any { it.body == "Hello from Alice's contact list" } && !model.room.value.chatSending }
        screenshot("contact-created-private-room.png")
        val aliceRoom = app.savedRooms.get(room)!!
        signOut(); app.savedRooms.reset() // Separate synthetic device vault for Bob.
        activity.scenario.onActivity { model.refreshSavedRooms() }
        signIn(62)
        ui.await("recipient invitation arrived without shared room") { model.start.value.contactInbox.invitations.any { it.room == room } }
        assertEquals(Stage.START, model.stage.value)
        // The invitation waits atop Chats (design-calm-shell 6.3).
        ui.await("invitation row") { ui.hasText("Alice Contact invited you to a private chat") }
        screenshot("contact-invitation-on-phone.png"); ui.click("Accept"); ui.room()
        assertEquals(room, model.room.value.roomId)
        ui.await("offline sender's history") { model.room.value.chat.any { it.body == "Hello from Alice's contact list" } }
        activity.scenario.onActivity { model.sendChat("Bob replied privately") }
        ui.await("reply saved") { !model.room.value.chatSending && model.room.value.chat.any { it.body == "Bob replied privately" } }
        screenshot("recipient-private-reply.png")
        signOut(); app.savedRooms.reset(); app.savedRooms.save(aliceRoom)
        activity.scenario.onActivity { model.refreshSavedRooms() }
        signIn(61); message("Bob Contact"); ui.room()
        assertEquals(room, model.room.value.roomId)
        ui.await("reply recovered") { model.room.value.chat.any { it.body == "Bob replied privately" } }
        val deliveries = relay.writes.filter { it.kind == 1059 }.map { it.id }.toSet()
        signOut(); activity.scenario.recreate()
        activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
        ui.home(); signIn(61); message("Bob Contact"); ui.room()
        assertEquals(room, model.room.value.roomId)
        assertEquals(deliveries, relay.writes.filter { it.kind == 1059 }.map { it.id }.toSet())
        assertEquals(1, model.start.value.savedRooms.count { it.privatePeer == bob.pubkey })
    }
}
