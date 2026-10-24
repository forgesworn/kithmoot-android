package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.state.ToggleableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The call relay password is hidden until its eye button is pressed. */
@RunWith(AndroidJUnit4::class)
class CallRelayDialogUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eyeShowsAndHidesTheRelayPassword() {
        compose.setContent { MaterialTheme { CallRelayDialog(onDismiss = {}) } }
        val field = compose.onNode(hasSetTextAction() and hasText("Relay password"))
        val eye = compose.onNodeWithContentDescription("Show relay password")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("Relay password") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        field.performTextReplacement("synthetic-relay-password")

        eye.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertEquals("•".repeat("synthetic-relay-password".length), shown(field))

        eye.performClick()
        eye.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertEquals("synthetic-relay-password", shown(field))

        eye.performClick()
        eye.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertEquals("•".repeat("synthetic-relay-password".length), shown(field))
    }

    /** What the field shows: its text after the password mask, if any. */
    private fun shown(field: SemanticsNodeInteraction): String? =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
}
