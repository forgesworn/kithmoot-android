package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * VMLS rooms and the restore witness need a Bothy box the person runs; most people have none, so they are
 * kept on a page of their own and named as a preview rather than offered among the everyday connections.
 * A row stays hidden when its screen is not supplied.
 */
@Composable
internal fun DeveloperPage(onRestoreWitness: (() -> Unit)?, onVmlsBoxes: (() -> Unit)?) {
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
}
