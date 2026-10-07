package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.settings.SettingsNavRow
import dev.forgesworn.kithmoot.ui.settings.SettingsRadioGroup
import dev.forgesworn.kithmoot.ui.settings.SettingsSwitchRow
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test

/** The settings rows are one target each: the words toggle, and the name is read once. */
class SettingsKitUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun a_switch_row_is_one_toggleable_node_named_once() {
        var on by mutableStateOf(false)
        compose.setContent { KithMootTheme { SettingsSwitchRow("Zen bell", "A soft bell", checked = on) { on = it } } }
        compose.onAllNodesWithText("Zen bell").assertCountEquals(1)
        compose.onNodeWithText("Zen bell").assert(hasClickAction()).assertIsToggleable().assertIsOff().assertHeightIsAtLeast(72.dp)
        compose.onNodeWithText("Zen bell").performClick()
        compose.onNodeWithText("Zen bell").assertIsOn()
    }

    @Test fun a_disabled_switch_row_does_not_toggle() {
        var on by mutableStateOf(false)
        compose.setContent { KithMootTheme { SettingsSwitchRow("Zen bell", checked = on, enabled = false) { on = it } } }
        compose.onNodeWithText("Zen bell").assertIsNotEnabled().assertHeightIsAtLeast(56.dp)
    }

    @Test fun a_nav_row_is_tall_enough_and_clicks() {
        var opened = false
        compose.setContent { KithMootTheme { SettingsNavRow("Nostr relays") { opened = true } } }
        compose.onNodeWithText("Nostr relays").assertHeightIsAtLeast(56.dp).performClick()
        assert(opened)
    }

    @Test fun a_radio_row_selects_through_its_words() {
        var chosen by mutableStateOf("Standard")
        compose.setContent { KithMootTheme { SettingsRadioGroup(listOf("Standard", "Large"), chosen, { it }) { chosen = it } } }
        compose.onNodeWithText("Large").performClick()
        compose.onNodeWithText("Large").assertIsSelected()
    }
}
