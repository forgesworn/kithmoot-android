package dev.forgesworn.kithmoot.storage

import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.WebAppAddress
import dev.forgesworn.kithmoot.ui.RoomViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Real settings and room flows, with a process boundary and an offline relay. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SiteAddressUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val ui = RecoveryUi()
    private val app get() = ApplicationProvider.getApplicationContext<KithMootApplication>()
    private val marker get() = File(app.noBackupFilesDir, "site-address-test.pid")
    private val origin = "https://circle.example:8443"

    @Test fun a_choose_site() {
        ui.home()
        activity.scenario.onActivity { assertTrue(ViewModelProvider(it)[RoomViewModel::class.java].onWebAppAddressChanged(WebAppAddress.DEFAULT_ORIGIN)) }
        ui.click("Site settings")
        ui.replace("Site address", "http://insecure.example")
        ui.assertEnabled("Save site", false)
        ui.click("Cancel")
        activity.scenario.onActivity { assertEquals(WebAppAddress.DEFAULT_ORIGIN, ViewModelProvider(it)[RoomViewModel::class.java].start.value.webAppAddress) }
        ui.click("Site settings")
        ui.replace("Site address", origin)
        ui.await("chosen site to appear") { ui.hasText(origin) }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir("ui-proof"), "site-address.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        ui.click("Save site")
        activity.scenario.onActivity { assertEquals(origin, ViewModelProvider(it)[RoomViewModel::class.java].start.value.webAppAddress) }
        app.savedRooms.reset()
        seedLegacyRoom(app, "Self hosted workshop")
        marker.writeText(Process.myPid().toString())
    }

    @Test fun b_reopen_and_share_without_the_workshop_site() {
        assertTrue("Run a_choose_site first", marker.isFile)
        if (InstrumentationRegistry.getArguments().getString("requireRestart") == "true") {
            assertNotEquals(marker.readText(), Process.myPid().toString())
        }
        ui.home()
        activity.scenario.onActivity { assertEquals(origin, ViewModelProvider(it)[RoomViewModel::class.java].start.value.webAppAddress) }
        ui.click("Self hosted workshop")
        ui.room()
        activity.scenario.onActivity {
            val model = ViewModelProvider(it)[RoomViewModel::class.java]
            assertTrue("Reopened room uses the chosen site", model.room.value.joinUrl.startsWith("$origin/j/#"))
            model.mintPairingLink()
        }
        ui.await("pairing link from the chosen site") {
            var ready = false
            activity.scenario.onActivity { ready = ViewModelProvider(it)[RoomViewModel::class.java].room.value.pairingLink?.startsWith("$origin/j/#") == true }
            ready
        }
        activity.scenario.onActivity { ViewModelProvider(it)[RoomViewModel::class.java].dismissPairingLink() }
        ui.click("Leave")
        ui.home()
        activity.scenario.onActivity { assertTrue(ViewModelProvider(it)[RoomViewModel::class.java].onWebAppAddressChanged(WebAppAddress.DEFAULT_ORIGIN)) }
    }
}
