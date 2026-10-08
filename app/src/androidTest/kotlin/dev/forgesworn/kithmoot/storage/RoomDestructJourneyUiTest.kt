package dev.forgesworn.kithmoot.storage

import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.countdownAccessibleName
import dev.forgesworn.kithmoot.ui.RoomViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Properties

/** Real activity, saved identity, sockets and expiry clock; no direct call to the destructor. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RoomDestructJourneyUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val ui = RecoveryUi()
    private val marker get() = File(app.noBackupFilesDir, "destruct-journey.properties")

    @Test fun a_expiry_deletes_the_open_room_and_shows_the_burst() {
        ui.home()
        ProjectTestRelay().use { relay ->
            val now = System.currentTimeMillis() / 1000
            val previousTombstones = app.selfDestructor.tombstones.list(now).map { it.id }.toSet()
            val secret = Entropy.bytes(32)
            val host = createRoomInvitation(true)
            val relays = listOf(relay.url)
            val saved = SavedRoom.create(secret, PrimaryIdentity.create(deriveRoom(secret).roomId, now + 86400, now),
                encodeInvitationUrl("https://kithmoot.forgesworn.dev/j/", host.invitation, relays), relays,
                "Expiry journey", now, host, host.invitation.canonicalInviter, ends = now + 3600, destruct = true)
            app.savedRooms.save(saved)
            lateinit var model: RoomViewModel
            activity.scenario.onActivity {
                model = ViewModelProvider(it)[RoomViewModel::class.java]
                model.refreshSavedRooms()
            }
            try {
                ui.click("Expiry journey")
                ui.room()
                activity.scenario.onActivity { model.sendChat("Burn this synthetic message") }
                ui.await("signed chat to reach the room's relay") {
                    relay.writes.any { it.kind == 1460 && it.pubkey == saved.devicePubkey }
                }
                val chat = relay.writes.first { it.kind == 1460 && it.pubkey == saved.devicePubkey }
                val inbox = BackgroundInboxVault(app, saved.id, saved.participant, saved.devicePubkey).inbox
                inbox.markRead(now)
                assertTrue(inbox.state().cursor > 0)
                val sequence = model.destructEffect.value
                val end = System.currentTimeMillis() / 1000 + 12
                app.savedRooms.update(saved.id) { it.withRoomLifetime(end, true, now) }
                ui.await("final countdown inside the open room") {
                    ui.hasDescription(countdownAccessibleName(end, now, true, System.currentTimeMillis() / 1000))
                }
                screenshot("self-destruct-journey-final-minute.png")
                ui.await("successful room cleanup to show its burst") { ui.hasText("Room self-destructed") }
                assertEquals(sequence + 1, model.destructEffect.value)
                assertNull("The saved room must be deleted before the success effect", app.savedRooms.get(saved.id))
                assertTrue(ui.hasText("The room is gone from this device"))
                screenshot("self-destruct-journey-burst.png")
                val requests = relay.writes.filter { it.kind == 5 && it.pubkey == saved.devicePubkey }
                assertTrue("The device must ask to delete its own chat", requests.any { it.tags.contains(listOf("e", chat.id)) })
                assertEquals(0L, inbox.state().cursor)
                assertTrue(runBlocking { PendingChatVault(app, saved.id, saved.participant, saved.devicePubkey).outbox.items().isEmpty() })
                ui.await("burst to finish") { !ui.hasText("Room self-destructed") }
                ui.home()
                assertFalse(ui.hasText("Expiry journey"))
                val tombstone = app.selfDestructor.tombstones.list(System.currentTimeMillis() / 1000)
                    .single { it.id !in previousTombstones }
                Properties().apply {
                    setProperty("id", saved.id)
                    setProperty("participant", saved.participant)
                    setProperty("device", saved.devicePubkey)
                    setProperty("tombstone", tombstone.id)
                    setProperty("pid", Process.myPid().toString())
                }.also { values -> marker.outputStream().use { values.store(it, "Synthetic expiry-test identifiers only") } }
            } finally {
                activity.scenario.onActivity { model.leave() }
                app.savedRooms.forget(saved.id)
            }
        }
    }

    @Test fun b_the_deleted_room_stays_gone_after_a_process_restart() {
        assertTrue("Run the expiry journey first", marker.isFile)
        val values = Properties().apply { marker.inputStream().use { load(it) } }
        if (InstrumentationRegistry.getArguments().getString("requireRestart") == "true") {
            assertNotEquals(values.getProperty("pid"), Process.myPid().toString())
        }
        ui.home()
        assertNull(app.savedRooms.get(values.getProperty("id")))
        assertFalse(ui.hasText("Expiry journey"))
        val room = values.getProperty("id")
        val person = values.getProperty("participant")
        val device = values.getProperty("device")
        assertEquals(0L, BackgroundInboxVault(app, room, person, device).inbox.state().cursor)
        assertTrue(runBlocking { PendingChatVault(app, room, person, device).outbox.items().isEmpty() })
        assertTrue(app.selfDestructor.tombstones.list(System.currentTimeMillis() / 1000)
            .any { it.id == values.getProperty("tombstone") })
        screenshot("self-destruct-journey-restarted.png")
        marker.delete()
    }

    private fun screenshot(name: String) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir("ui-proof"), name).outputStream().use {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }
}
