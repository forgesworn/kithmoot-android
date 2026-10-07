package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.RoomBookmarkSnapshot
import dev.forgesworn.kithmoot.ui.settings.SettingsActionRow
import dev.forgesworn.kithmoot.ui.settings.SettingsDisclosure
import dev.forgesworn.kithmoot.ui.settings.SettingsNote
import dev.forgesworn.kithmoot.ui.settings.SettingsSection
import dev.forgesworn.kithmoot.ui.settings.changesWaiting

data class AccountRoomActions(
    val refresh: () -> Unit = {},
    val open: (dev.forgesworn.kithmoot.account.AccountRoom) -> Unit = {},
    val remove: (String) -> Unit = {},
    val importRooms: () -> Unit = {},
)

/**
 * "Rooms on your devices", on the Account page: the account-wide sync and
 * import controls (the list itself lives in the merged home rows).
 */
@Composable
internal fun AccountSyncSection(state: RoomBookmarkSnapshot, importableCount: Int, enabled: Boolean, actions: AccountRoomActions) {
    var importing by remember { mutableStateOf(false) }
    SettingsSection("Rooms on your devices") {
        SettingsNote(when (state.rooms.size) {
            0 -> "No rooms in your account yet"
            1 -> "1 room in your account"
            else -> "${state.rooms.size} rooms in your account"
        })
        if (state.pending > 0) SettingsNote(changesWaiting(state.pending), live = true)
        if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { contentDescription = "Syncing rooms" })
        state.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        SettingsActionRow(if (state.error != null || state.pending > 0) "Retry sync" else "Sync now", enabled = enabled && !state.syncing, onClick = actions.refresh)
        if (importableCount > 0) SettingsActionRow(
            if (importableCount == 1) "Add the room on this phone to your account" else "Add the $importableCount rooms on this phone to your account",
            enabled = enabled && !state.syncing,
        ) { importing = true }
        SettingsDisclosure("Missing a room?") {
            SettingsNote("Use the same Nostr account on both devices, with at least one relay in common. In a browser, add any browser-only rooms to your account first. " +
                "Opening a restored room may need another member to be online; messages load from whatever history is still available.")
        }
    }
    if (importing) AlertDialog(onDismissRequest = { importing = false },
        title = { Text("Add this phone's rooms to your account?") },
        text = { Text("Names and invitation links for rooms joined with this account will be encrypted to your Nostr key and saved on your relays. Other devices using this account can then find them. Guest and anonymous rooms stay on this phone.") },
        confirmButton = { TextButton({ importing = false; actions.importRooms() }) { Text("Add rooms") } },
        dismissButton = { TextButton({ importing = false }) { Text("Cancel") } })
}
