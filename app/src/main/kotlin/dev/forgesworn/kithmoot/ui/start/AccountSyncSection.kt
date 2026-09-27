package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.forgesworn.kithmoot.account.RoomBookmarkSnapshot

data class AccountRoomActions(
    val refresh: () -> Unit = {},
    val open: (dev.forgesworn.kithmoot.account.AccountRoom) -> Unit = {},
    val remove: (String) -> Unit = {},
    val importRooms: () -> Unit = {},
)

/**
 * Account room sync and import, in Settings under "You" (moved from the
 * deleted AccountRoomsPanel: the list itself now lives in the merged home
 * rows, so this is only the account-wide controls).
 */
@Composable
internal fun AccountSyncSection(state: RoomBookmarkSnapshot, importableCount: Int, enabled: Boolean, actions: AccountRoomActions) {
    var importing by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.rooms.isNotEmpty()) Text("${state.rooms.size} ${if (state.rooms.size == 1) "room" else "rooms"} in your account",
            style = MaterialTheme.typography.bodySmall)
        if (state.pending > 0) Text("${state.pending} ${if (state.pending == 1) "change" else "changes"} waiting to sync.")
        if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Syncing rooms" })
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        TextButton(actions.refresh, enabled = enabled && !state.syncing) {
            Text(if (state.error != null || state.pending > 0) "Retry sync" else "Sync now")
        }
        if (importableCount > 0) TextButton({ importing = true }, enabled = enabled && !state.syncing) {
            Text(if (importableCount == 1) "Add the room on this phone to your account" else "Add the $importableCount rooms on this phone to your account")
        }
        TextButton({ help = !help }) { Text(if (help) "Hide sync help" else "Missing a room?") }
        if (help) Text(
            "Use the same Nostr account and overlapping relays on both devices. In the browser, add any browser-only rooms to your account. Opening a restored room may need another member online; messages load from the available history.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (importing) AlertDialog(onDismissRequest = { importing = false },
        title = { Text("Add this phone's rooms to your account?") },
        text = { Text("Names and invitation links for rooms joined with this account will be encrypted to your Nostr key and saved on your relays. Other devices using this account can then find them. Guest and anonymous rooms stay on this phone.") },
        confirmButton = { TextButton({ importing = false; actions.importRooms() }) { Text("Add rooms") } },
        dismissButton = { TextButton({ importing = false }) { Text("Cancel") } })
}
