package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Calls page for a screen reader: its summary head, and its rows in the order the design gives. */
@RunWith(AndroidJUnit4::class)
class CallsPageUiTest {
    @get:Rule val compose = createComposeRule()

    private val view = CallPathWords.view(CallPathFacts(builtInRow = "Off", nowMs = 0))

    @Test fun summaryHeadIsAPoliteLiveRegionHeading() {
        compose.setContent { MaterialTheme { CallsPage(view, hideIpOnCalls = false, onHideIpOnCalls = {}, publicShown = true, onOpen = {}) } }
        compose.onNodeWithText("Direct only")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun everyRowOpensItsPage() {
        val opened = mutableListOf<SettingsPage>()
        compose.setContent { MaterialTheme { CallsPage(view, hideIpOnCalls = false, onHideIpOnCalls = {}, publicShown = true, onOpen = { opened += it }) } }
        for ((title, page) in listOf("Call relay on this phone" to SettingsPage.CALL_RELAY, "Your Spry node" to SettingsPage.SPRY,
            "Relay server" to SettingsPage.RELAY_SERVER, "Public call relays" to SettingsPage.PUBLIC_RELAYS, "Advanced" to SettingsPage.CALL_ADVANCED)) {
            compose.onNodeWithText(title).assertHasClickAction().performClick()
            assertEquals(page, opened.last())
        }
        assertEquals(5, opened.size)
        assertEquals(1, compose.onAllNodesWithText("Hide my IP address on calls").fetchSemanticsNodes().size)
    }
}
