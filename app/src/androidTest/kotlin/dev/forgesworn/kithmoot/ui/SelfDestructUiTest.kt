package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.ui.room.CountdownPill
import dev.forgesworn.kithmoot.ui.room.FinalMinuteBanner
import dev.forgesworn.kithmoot.ui.start.TombstoneRow
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * The countdown's stages as drawn: each pill with its words and fuse, its
 * spoken name, the final minute's banner and a tombstone row, in both themes.
 * Screenshots land in the app's ui-proof folder for a person to look at.
 */
class SelfDestructUiTest {
    @get:Rule val compose = createComposeRule()

    private val day = 86_400L
    private val hour = 3_600L

    private fun show(dark: Boolean, onDismiss: () -> Unit = {}) {
        val now = epochSeconds()
        val week = 7 * day
        compose.setContent {
            KithMootTheme(darkTheme = dark) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Self-destruct countdown", color = MaterialTheme.colorScheme.onBackground)
                    // A seven-day room: green, amber at a day, red at an hour, then the final minute.
                    CountdownPill(now + 4 * day, now + 4 * day - week, destruct = true, now = now)
                    CountdownPill(now + 5 * hour + 12 * 60, now - week, destruct = true, now = now)
                    CountdownPill(now + 42 * 60 + 7, now - week, destruct = true, now = now)
                    CountdownPill(now + 45, now - week, destruct = true, now = now)
                    // A room that ends and keeps a read-only copy: grey.
                    CountdownPill(now + 4 * day, now - day, destruct = false, now = now)
                    FinalMinuteBanner(now + 50)
                    TombstoneRow(1_791_560_580L, ZoneId.of("UTC"), Locale.UK, onDismiss)
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.onRoot().printToLog("SelfDestructUiTest")
        // The window is drawn after composition settles; give the frame time to reach the screen.
        Thread.sleep(1_000)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.getExternalFilesDir("ui-proof"), name).outputStream().use {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    @Test fun everyStageHasWordsASpokenNameAndItsColour() {
        var dismissed = 0
        show(dark = false) { dismissed++ }
        compose.onNodeWithText("Self-destructs in 4 days", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Self-destructs in 4 days").assertIsDisplayed()
        compose.onNodeWithText("Self-destructs in 5 h 12 m", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Self-destructs in 5 hours 12 minutes").assertIsDisplayed()
        compose.onNodeWithText("Self-destructs in 42:07", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Self-destructs in under 43 minutes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Self-destructs in under 1 minute").assertIsDisplayed()
        compose.onNodeWithText("Ends in 4 days", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("This room self-destructs in under a minute. Save anything you need now.").assertIsDisplayed()
        compose.onNodeWithText("A room self-destructed · Fri 9 Oct, 15:43", useUnmergedTree = true).assertExists()
        screenshot("self-destruct-light.png")
        compose.onNodeWithContentDescription("Dismiss: A room self-destructed · Fri 9 Oct, 15:43").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun theStagesInTheDarkTheme() {
        show(dark = true)
        compose.onNodeWithText("Self-destructs in 4 days", useUnmergedTree = true).assertExists()
        screenshot("self-destruct-dark.png")
    }
}
