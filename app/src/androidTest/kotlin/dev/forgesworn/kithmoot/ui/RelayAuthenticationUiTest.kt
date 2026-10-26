package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.ui.settings.RelaysPage
import dev.forgesworn.kithmoot.ui.settings.rememberRelayEditor
import dev.forgesworn.kithmoot.ui.start.AccountSettingsActions
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Fixed public fixture data only; never connects to a relay or real signer. */
class RelayAuthenticationUiTest {
    @get:Rule val compose = createComposeRule()
    private val url = "wss://relay.example"
    private val key = "03".repeat(32)
    private fun fixture() = StartState(account = AccountView(key, npubOf(key), "npub1test…", "local"),
        relayChoices = listOf(RelayChoice(url)), relayAuthenticationEpoch = 7, loadingRooms = false)

    @Test fun confirmation_cancel_approve_and_withdraw_are_explicit() {
        var state by mutableStateOf(fixture())
        val choices = mutableListOf<Boolean>()
        compose.setContent { KithMootTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RelaysPage(state, rememberRelayEditor(state.relayChoices, state.account?.pubkey), false,
                    AccountSettingsActions(roomRelayAuthentication = { requested, epoch, enabled ->
                        assertEquals(url, requested); assertEquals(7L, epoch)
                        choices += enabled
                        state = state.copy(roomRelayAuthentication = if (enabled) setOf(url) else emptySet())
                        null
                    }))
            }
        } }
        compose.onNodeWithText("Use signed-in account").performClick()
        compose.onNodeWithText("Identify to this relay?").assertIsDisplayed()
        capture("consent")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<Boolean>(), choices)
        compose.onNodeWithText("Use signed-in account").performClick()
        compose.onNodeWithText("Use this account").performClick()
        compose.onNodeWithText("Stop identifying").assertIsDisplayed()
        capture("permitted")
        compose.onNodeWithText("Stop identifying").performClick()
        assertEquals(listOf(true, false), choices)
        compose.onNodeWithText("Use signed-in account").assertIsDisplayed()
    }

    @Test fun account_change_dismisses_an_unconfirmed_grant() {
        var state by mutableStateOf(fixture())
        compose.setContent { KithMootTheme {
            RelaysPage(state, rememberRelayEditor(state.relayChoices, state.account?.pubkey), false, AccountSettingsActions())
        } }
        compose.onNodeWithText("Use signed-in account").performClick()
        compose.runOnIdle { state = state.copy(relayAuthenticationEpoch = 8) }
        compose.onNodeWithText("Identify to this relay?").assertDoesNotExist()
    }

    @Test fun permission_cannot_change_inside_a_room() {
        val state = fixture()
        compose.setContent { KithMootTheme {
            RelaysPage(state, rememberRelayEditor(state.relayChoices, state.account?.pubkey), true, AccountSettingsActions())
        } }
        compose.onNodeWithText("Use signed-in account").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "relay-auth").also { it.mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
