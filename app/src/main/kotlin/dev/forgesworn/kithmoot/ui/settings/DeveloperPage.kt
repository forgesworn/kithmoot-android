package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * VMLS rooms and the restore witness need a Bothy box the person runs; most people have none, so they are
 * kept on a page of their own and named as a preview rather than offered among the everyday connections.
 * A row stays hidden when its screen is not supplied.
 */
@Composable
internal fun DeveloperPage(onRestoreWitness: (() -> Unit)?, onVmlsBoxes: (() -> Unit)?, callRelay: CallRelayPanel? = null) {
    SettingsSection(null) {
        Text(
            if (admin2ProductSelected) "Private groups use your Bothy home and restore witness. Group admins manage membership through a manual authenticated private invitation ceremony, with one shared paired home."
            else "For people who run their own Bothy box: end-to-end encrypted VMLS rooms hosted on it, and its restore witness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onRestoreWitness?.let { SettingsNavRow("Restore witness", "Enrol this account's vault at your Bothy box", onClick = it) }
        onVmlsBoxes?.let { SettingsNavRow(if (admin2ProductSelected) "Private invitations and paired home" else "VMLS boxes", "Pair a home and manage this account's MLS rooms", onClick = it) }
    }
    if (dev.forgesworn.kithmoot.BuildConfig.DEBUG) callRelay?.let { CallRelayDeveloper(it) }
}

/** Debug builds only: pin the call relay page to any of its states, and remove the relay's keys and grants. */
@Composable
private fun CallRelayDeveloper(panel: CallRelayPanel) {
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
