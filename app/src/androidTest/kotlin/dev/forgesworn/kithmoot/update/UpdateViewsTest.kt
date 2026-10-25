package dev.forgesworn.kithmoot.update

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test

class UpdateViewsTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun twoAndThreeReleaseNotesAreShownInOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                KithMootTheme { UpdateReleaseNotes(listOf("First improvement", "Second improvement", "Third improvement")) }
            } }
            for (note in listOf("First improvement", "Second improvement", "Third improvement")) {
                ui.onNodeWithText(note).assertIsDisplayed()
            }
            ui.onAllNodesWithText("•").assertCountEquals(3)
        }
    }

    @Test fun releaseNotesWrapAtLargeTextAndMissingNotesEmitNothing() {
        val first = "A longer release note that wraps onto more than one line at a narrow width and large text size."
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                KithMootTheme(textScale = 2.0f) { Surface(Modifier.width(280.dp)) {
                    UpdateReleaseNotes(listOf(first, "The second improvement stays readable too."))
                } }
            } }
            ui.onNodeWithText(first).assertIsDisplayed()
            ui.onNodeWithText("The second improvement stays readable too.").performScrollTo().assertIsDisplayed()
            scenario.onActivity { activity -> activity.setContent {
                KithMootTheme { UpdateReleaseNotes(emptyList()) }
            } }
            ui.onAllNodesWithText("•").assertCountEquals(0)
        }
    }

    @Test fun longNotesLeaveUpdateActionsReachable() {
        val notes = listOf("First", "Second", "Third").map { label ->
            "$label: " + "A release improvement with enough detail to wrap at a large text size. ".repeat(2).take(130)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                KithMootTheme(textScale = 2.0f) { Surface(Modifier.width(280.dp).height(480.dp)) {
                    Column {
                        UpdateReleaseNotes(notes)
                        Button(onClick = {}) { Text("Install") }
                    }
                } }
            } }
            ui.onNodeWithText("Install").assertIsDisplayed()
            ui.onNodeWithText(notes.last()).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Install").assertIsDisplayed()
        }
    }
}
