package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.theme.TextSize

/** Display: how big the words are in KithMoot, on top of the phone's own size. */
@Composable
internal fun DisplayPage() {
    SettingsSection("Text size") {
        val textSetting = LocalTextSizeSetting.current
        SettingsRadioGroup(TextSize.entries, textSetting.size, { it.label }, textSetting.set)
        Text("Messages, names and menus will look like this.", style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        SettingsNote("Changes the words in KithMoot only, not the rest of your phone.")
        if (LocalContext.current.resources.configuration.fontScale > 1f) {
            SettingsNote("This adds to your phone's own text size, up to twice the standard size.")
        }
    }
}
