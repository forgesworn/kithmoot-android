package dev.forgesworn.kithmoot.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.ContactInvitations
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.NostrAccount
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.service.BackgroundCallListenerService
import dev.forgesworn.kithmoot.session.dmPolicy
import dev.forgesworn.kithmoot.storage.ContactAccountVault
import dev.forgesworn.kithmoot.ui.GuestAdmissionEntryTest
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.relayChoiceEntries
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A dedicated emulator, the real Android service and an explicitly configured fixture signer. */
class PrivateInvitationAlertTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Test fun closedAppReceivesAFirstContactInvitationAndPostsOnlyGenericText() = runBlocking {
        val fixture = GuestAdmissionEntryTest.Fixture(ack = true)
        val app = fixture.app
        assertNull("Use an isolated emulator with no signed-in account", app.accounts.load())
        assertTrue(app.savedRooms.list().isEmpty())
        val signerPackage = "dev.forgesworn.kithmoot.g5signer"
        val bob = LocalSigner(Digests.sha256("kithmoot-g5-fixture-signer-v2:bob".toByteArray()))
        val alice = LocalSigner(ByteArray(32) { 21 })
        val alerts = PrivateInvitationAlerts(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val prefs = app.getSharedPreferences("kithmoot.display", Context.MODE_PRIVATE)
        val previous = mutableMapOf<String, String?>()
        try {
            shell("am start -W -a android.intent.action.VIEW -d nostrsigner: -p $signerPackage --es type g5_configure --es persona bob --ez privateInvitations true")
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
            fixture.start()
            val relay = fixture.server.url("/").toString().replace("http:", "ws:")
            val desired = relayChoiceEntries(bob.pubkey, listOf(RelayChoice(relay)))
            desired.keys.forEach { previous[it] = prefs.getString(it, null) }
            val edit = prefs.edit(); desired.forEach { (key, value) -> edit.putString(key, value) }; check(edit.commit())
            val now = fixture.now()
            app.accounts.save(NostrAccount(bob.pubkey, "nip55", signerPackage = signerPackage, signedInAt = now))
            alerts.setEnabled(bob.pubkey, true, now - 1)
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(20_000) { while (!BackgroundCallListenerService.alive) delay(50) }
            }
            shell("input keyevent KEYCODE_SLEEP")
            val host = createRoomInvitation(persistent = true)
            val link = encodeInvitationUrl("https://fixture.invalid/j/", host.invitation, listOf(relay), dmPolicy(alice.pubkey, bob.pubkey))
            val rumor = ContactInvitations.rumor(alice.pubkey, bob.pubkey, fixture.room.roomId, link, now)
            val id = ContactInvitations.read(rumor, bob.pubkey, now)!!.id
            val wrapped = ContactInvitations.wrap(rumor, alice, bob.pubkey, now) { true }
            val notice = withTimeout(30_000) {
                var found = manager.activeNotifications.firstOrNull { it.tag == id }
                while (found == null) {
                    fixture.reply(wrapped)
                    delay(150)
                    found = manager.activeNotifications.firstOrNull { it.tag == id }
                }
                found.notification
            }
            assertEquals("KithMoot", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("New private conversation invitation", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertNotNull(notice.contentIntent)
            assertNull(app.savedRooms.get(fixture.room.roomId))
            assertTrue(ContactAccountVault.peek(app, bob.pubkey, "inbox")?.contains(id) == true)
            repeat(3) { fixture.reply(wrapped); delay(150) }
            assertEquals(1, manager.activeNotifications.count { it.tag == id })
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it; notice.contentIntent.send() }
            val sender = shortNpub(alice.pubkey)
            val reviewed = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                while (compose.onAllNodesWithText("Invitation from $sender").fetchSemanticsNodes().isEmpty()) delay(100)
                true
            }
            var inboxState = "No resumed activity"
            instrumentation.runOnMainSync {
                if (activity in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    val state = ViewModelProvider(activity)[RoomViewModel::class.java].start.value
                    val locked = app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                    inboxState = "Account restored=${state.account?.pubkey == bob.pubkey}, inbox ready=${state.contactInbox.ready}, expected item=${state.contactInbox.invitations.any { it.id == id }}, locked=$locked, ${state.contactsError.orEmpty()}"
                }
                activity.finish()
            }
            assertTrue("Tap should show the exact request. $inboxState", reviewed == true)
            assertNull("Reviewing the invitation must not open its room", app.savedRooms.get(fixture.room.roomId))
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            alerts.setEnabled(bob.pubkey, false)
            BackgroundCallListenerService.stop(app)
            withTimeout(10_000) { while (BackgroundCallListenerService.alive) delay(50) }
            app.accounts.clear()
            val edit = prefs.edit()
            previous.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
            check(edit.commit())
            fixture.close()
        }
    }
}
