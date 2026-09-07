package dev.forgesworn.kithmoot.storage

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.RoomViewModel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Driven by the reference repo's check-m2-android-keeper.mjs on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class M2KeeperInteropTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun previousKeeperAndNativeClientExchangeChat() {
        val link = InstrumentationRegistry.getArguments().getString("m2RoomLink")
        assumeTrue("Requires the synthetic previous-release keeper fixture", link != null)
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val ui = RecoveryUi()
        ui.home()
        app.savedRooms.reset()
        activity.scenario.onActivity {
            val model = ViewModelProvider(it)[RoomViewModel::class.java]
            model.refreshSavedRooms()
            model.joinFromUrl(link!!)
        }
        ui.room()
        activity.scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].sendChat("M2 Android to previous keeper") }
        ui.click("Chat")
        ui.await("encrypted reply from the previous-release keeper") { ui.hasText("Previous keeper to M2 Android") }
        ui.click("Close sheet")
        ui.click("Leave")
    }
}
