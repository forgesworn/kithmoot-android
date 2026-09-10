package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.room.ContactCardsSheet
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Production consent controls; protocol, encrypted-state and transport
 * behaviour are covered separately. This test grants no live-box evidence. */
class BoxDiscoveryUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun consent_cancel_check_stop_and_forget_are_explicit() {
        val box = ContactBoxRow("02".repeat(32), "original-card", "Box status is not being checked.", false)
        val contact = ContactRow("01".repeat(32), "npub1fixture", "Rowan", listOf(box), System.currentTimeMillis() / 1000 + 86400)
        val held = mutableStateOf(listOf(contact))
        val calls = mutableListOf<Boolean>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                KithMootTheme { ContactCardsSheet(
                    contacts = held.value, status = null, myCard = null, canShowCard = false,
                    onAdd = {}, onForget = { held.value = emptyList() }, onShowMyCard = {}, onDone = {},
                    readRelays = "wss://read.example/",
                    onCheckBox = { p, b, revision, on ->
                        assertEquals(contact.p, p); assertEquals(box.p, b); assertEquals(box.revision, revision)
                        calls += on
                        held.value = listOf(contact.copy(boxes = listOf(box.copy(checking = on, description = if (on) "Verified message endpoint: wss://owned.example/drops" else box.description))))
                    },
                ) }
            } }
            ui.onNodeWithText("Check box status").performScrollTo().performClick()
            ui.onNodeWithText("Check this box?").assertIsDisplayed()
            ui.onNode(hasText("These read relays will see", substring = true)).assertIsDisplayed()
            ui.onNodeWithText("Cancel").performClick()
            ui.runOnIdle { assertTrue(calls.isEmpty()) }
            ui.onNodeWithText("Check box status").performClick()
            ui.onNodeWithText("Check box", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Verified message endpoint: wss://owned.example/drops").assertIsDisplayed()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val image = instrumentation.uiAutomation.takeScreenshot()
            val file = File(instrumentation.targetContext.getExternalFilesDir("ui-proof"), "box-discovery.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            ui.onNodeWithText("Stop checking box").performClick()
            ui.runOnIdle { assertEquals(listOf(true, false), calls) }
            ui.onNodeWithText("Box status is not being checked.").assertIsDisplayed()
            ui.onNodeWithText("Forget").performClick()
            ui.onNodeWithText("Keep").performClick()
            ui.onNodeWithText("Forget").performClick()
            ui.onNodeWithText("Forget card").performClick()
            ui.onNodeWithText("No contact cards yet.").assertIsDisplayed()

        }
    }
}
