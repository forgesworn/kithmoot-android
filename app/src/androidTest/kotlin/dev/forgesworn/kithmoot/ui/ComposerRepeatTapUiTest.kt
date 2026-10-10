package dev.forgesworn.kithmoot.ui

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeJoinUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.PendingChatVault
import dev.forgesworn.kithmoot.storage.RecoveryUi
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Disposable emulator only: real model, crypto, local journal and loopback relay. */
class ComposerRepeatTapUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun repeated_taps_keep_one_draft_and_the_next_draft_can_queue_without_a_relay_receipt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val ui = RecoveryUi()
        lateinit var model: RoomViewModel
        ProjectTestRelay().use { server ->
            activity.scenario.onActivity { model = ViewModelProvider(it)[RoomViewModel::class.java] }
            ui.home()
            if (model.start.value.account != null) {
                activity.scenario.onActivity { model.signOut() }
                ui.await("previous synthetic account closed") { model.start.value.account == null }
            }
            app.savedRooms.reset()
            val signer = LocalSigner(ByteArray(32) { 17 })
            activity.scenario.onActivity { model.onRelaysChanged(server.url); model.installLocalTestAccount(ByteArray(32) { 17 }, true) }
            ui.await("synthetic account ready") { model.start.value.account?.pubkey == signer.pubkey }
            val secret = ByteArray(32) { 23 }; val room = deriveRoom(secret)
            val now = System.currentTimeMillis() / 1000
            val who = PrimaryIdentity.createWith(signer, room.roomId, now + 3600, now)
            val saved = SavedRoom.create(secret, who, encodeJoinUrl("https://kithmoot.example/j/", secret, listOf(server.url)),
                listOf(server.url), "Repeat-tap fixture", now, null, null)
            app.savedRooms.save(saved)
            activity.scenario.onActivity { model.refreshSavedRooms() }
            ui.await("saved fixture ready") { model.start.value.savedRooms.any { it.id == saved.id } }
            activity.scenario.onActivity { model.reopenRoom(saved.id) }
            ui.room()
            ui.await("loopback relay connected") { model.room.value.relaysUp > 0 }
            try {
                server.acknowledge = false
                val retained = AtomicInteger()
                activity.scenario.onActivity {
                    repeat(4) { model.sendChat("First slow draft") { retained.incrementAndGet() } }
                    assertTrue("Send must claim the composer before the main thread can receive the retention callback", model.room.value.chatSending)
                }
                ui.await("first draft retained and composer released") { retained.get() > 0 && !model.room.value.chatSending }
                assertEquals("Four taps before retention must create one submission", 1, retained.get())
                val current = requireNotNull(app.savedRooms.get(saved.id))
                val outbox = PendingChatVault(app, current.id, current.participant, current.devicePubkey).outbox
                assertEquals(listOf("First slow draft"), outbox.items().map { it.text })
                activity.scenario.onActivity {
                    repeat(4) { model.sendChat("Next distinct draft") { retained.incrementAndGet() } }
                    assertTrue(model.room.value.chatSending)
                }
                ui.await("next draft retained without waiting for the first relay receipt") { retained.get() >= 2 && !model.room.value.chatSending }
                assertEquals(2, retained.get())
                val pending = outbox.items()
                assertEquals(listOf("First slow draft", "Next distinct draft"), pending.map { it.text })
                assertEquals(2, pending.map { it.event.id }.distinct().size)
                // A refused empty submission releases the composer and calls no retention callback.
                activity.scenario.onActivity { model.sendChat(" ") { retained.incrementAndGet() } }
                ui.await("empty submission released") { !model.room.value.chatSending }
                assertEquals(2, retained.get())
                // Deliberately sending the same text as a new draft is still allowed.
                activity.scenario.onActivity { model.sendChat("First slow draft") { retained.incrementAndGet() } }
                ui.await("intentional repeated text retained") { retained.get() == 3 && !model.room.value.chatSending }
                assertEquals(listOf("First slow draft", "Next distinct draft", "First slow draft"), outbox.items().map { it.text })
                assertEquals(3, outbox.items().map { it.event.id }.distinct().size)
            } finally {
                activity.scenario.onActivity { model.leave() }
                ui.await("fixture closed") { model.stage.value == Stage.START && !model.start.value.busy }
                activity.scenario.onActivity { model.signOut() }
                ui.await("synthetic account closed") { model.start.value.account == null }
            }
        }
    }
}
