package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.ui.start.PersonaMenuActions
import dev.forgesworn.kithmoot.ui.start.ShellAccountButton
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShellAccountUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun accountActionsStayReadableAndReachableAtLargeText() {
        val me = "03".repeat(32)
        val account = AccountView(me, npubOf(me), "npub1ada…", "local", name = "Ada")
        val other = "04".repeat(32)
        val persona = AccountView(other, npubOf(other), "npub1work…", "local", personaLabel = "Work persona")
        var settings = 0
        var switched: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                KithMootTheme(textScale = 2.0f) {
                    ShellAccountButton(account, showPicture = false, busy = false,
                        onSettings = { settings++ }, onSignIn = {}, onSignOut = {},
                        personaState = StartState(account = account, personas = listOf(account, persona)),
                        personaActions = PersonaMenuActions({}, { _, _ -> }, { pubkey, _ -> switched = pubkey }, {}))
                }
            } }
            ui.onNodeWithContentDescription("Account menu for Ada").performClick()
            ui.onNodeWithText("Current persona").assertIsDisplayed()
            ui.onNodeWithText("Ada").assertIsDisplayed()
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            for (label in listOf("Work persona", "Settings", "Sign out")) {
                val bounds = ui.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot
                assertTrue("$label target is too small: ${bounds.height}", bounds.height >= 56 * density)
            }
            ui.onNodeWithText("Settings").performClick()
            assertEquals(1, settings)
            ui.onNodeWithContentDescription("Account menu for Ada").performClick()
            ui.onNodeWithText("Work persona").performClick()
            assertEquals(other, switched)
        }
    }
}
