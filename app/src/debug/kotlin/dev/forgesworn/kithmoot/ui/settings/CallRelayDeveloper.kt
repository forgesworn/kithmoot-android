package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Debug builds only: pin the call relay page to any of its states, and remove the relay's keys and grants. */
@Composable
internal fun CallRelayDeveloper(panel: CallRelayPanel) {
    val setPreview = panel.setPreview ?: return
    val picked = panel.preview?.collectAsState()?.value
    val entries = remember { CallRelayPreviews.entries(dev.forgesworn.kithmoot.BuildConfig.DEBUG) }
    var removing by remember { mutableStateOf(false) }
    SettingsSection("Call relay screen preview (debug build)") {
        SettingsNote("Pins the Call relay on this phone page (Connections) to a state, so each screen can be seen without hosting. Choose Live to go back.")
        SettingsRadioGroup(listOf<CallRelayPreviews.Entry?>(null) + entries, entries.firstOrNull { it.id == picked },
            label = { it?.let { entry -> "${entry.id}: ${entry.label}" } ?: "Live" }, onSelect = { setPreview(it?.id) })
        SettingsNavRow("Remove the call relay", "Stop it and forget its keys and grants on this phone") { removing = true }
    }
    if (removing) androidx.compose.material3.AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(CallRelayWords.REMOVE_KEYS_TITLE) },
        text = { Text(CallRelayWords.REMOVE_KEYS_MESSAGE) },
        dismissButton = { androidx.compose.material3.TextButton({ removing = false }) { Text(CallRelayWords.ON_CANCEL) } },
        confirmButton = { androidx.compose.material3.TextButton({ removing = false; panel.removeKeys() }) { Text(CallRelayWords.REMOVE_KEYS_CONFIRM) } },
    )
}
