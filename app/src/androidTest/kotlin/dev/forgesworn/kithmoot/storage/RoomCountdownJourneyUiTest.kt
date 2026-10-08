package dev.forgesworn.kithmoot.storage

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.countdownAccessibleName
import dev.forgesworn.kithmoot.ui.RoomViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Open the installed app's saved room, rather than displaying an isolated countdown widget. */
@RunWith(AndroidJUnit4::class)
class RoomCountdownJourneyUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun a_saved_rooms_countdown_is_visible_inside_chat_work_and_call() = openRoomCountdown(false)

    @Test fun a_deadline_learned_while_the_room_is_open_reaches_the_countdown() = openRoomCountdown(true)

    private fun openRoomCountdown(learnWhileOpen: Boolean) {
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val ui = RecoveryUi()
        ui.home()
        val now = System.currentTimeMillis() / 1000
        val end = now + 3 * 3600
        val secret = Entropy.bytes(32)
        val host = createRoomInvitation(true)
        val relays = listOf("ws://10.0.2.2:59999")
        val room = SavedRoom.create(secret, PrimaryIdentity.create(deriveRoom(secret).roomId, now + 86400, now),
            encodeInvitationUrl("https://kithmoot.forgesworn.dev/j/", host.invitation, relays), relays,
            "Countdown journey", now, host, host.invitation.canonicalInviter,
            ends = end.takeUnless { learnWhileOpen }, destruct = !learnWhileOpen)
        app.savedRooms.save(room)
        lateinit var model: RoomViewModel
        activity.scenario.onActivity {
            model = ViewModelProvider(it)[RoomViewModel::class.java]
            model.refreshSavedRooms()
        }
        try {
            ui.home()
            ui.click("Countdown journey")
            ui.room()
            if (learnWhileOpen) {
                app.savedRooms.update(room.id) { it.withRoomLifetime(end, true, now) }
                activity.scenario.onActivity { model.refreshSavedRooms() }
                ui.await("open room to learn the saved deadline") { model.room.value.endsAt == end }
            }
            assertEquals(end, model.room.value.endsAt)
            assertTrue(model.room.value.destruct)
            fun countdownVisible() = ui.await("countdown inside the open room") {
                ui.hasDescription(countdownAccessibleName(end, now, true, System.currentTimeMillis() / 1000))
            }
            countdownVisible()
            var topInset = 0
            activity.scenario.onActivity {
                topInset = it.window.decorView.rootWindowInsets.getInsets(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()).top
            }
            assertTrue("The countdown must sit below the status bar and camera cutout",
                ui.descriptionBounds(countdownAccessibleName(end, now, true, System.currentTimeMillis() / 1000)).top >= topInset)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val shot = instrumentation.uiAutomation.takeScreenshot()
            File(app.getExternalFilesDir("ui-proof"), "self-destruct-room-journey.png").outputStream().use {
                shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            shot.recycle()
            ui.click("Work")
            countdownVisible()
            ui.click("Call")
            countdownVisible()
            ui.click("Chat")
            countdownVisible()
        } finally {
            activity.scenario.onActivity { model.leave() }
            ui.home()
            app.savedRooms.forget(room.id)
        }
    }
}
