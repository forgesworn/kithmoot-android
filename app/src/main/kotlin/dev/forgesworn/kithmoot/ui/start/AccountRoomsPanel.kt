package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.AccountRoom
import dev.forgesworn.kithmoot.account.RoomBookmarkSnapshot

data class AccountRoomActions(
    val refresh: () -> Unit = {},
    val open: (AccountRoom) -> Unit = {},
    val remove: (String) -> Unit = {},
    val importRooms: () -> Unit = {},
)

@Composable
fun AccountRoomsPanel(state: RoomBookmarkSnapshot, savedIds: Set<String>, importableCount: Int,
    enabled: Boolean, actions: AccountRoomActions,
) {
    var query by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<AccountRoom?>(null) }
    var importing by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Chats and rooms", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(actions.refresh, enabled = enabled && !state.syncing) {
                Text(if (state.error != null || state.pending > 0) "Retry sync" else "Sync")
            }
        }
        if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Syncing rooms" })
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.pending > 0) Text("${state.pending} ${if (state.pending == 1) "change" else "changes"} waiting to sync.")
        if (!state.syncing && state.ready && state.rooms.isEmpty()) {
            Text("No synced conversations yet.")
        }
        if (importableCount > 0) TextButton({ importing = true }, enabled = enabled && !state.syncing) {
            Text("Add this phone's rooms to your account ($importableCount)")
        }
        val remoteRooms = state.rooms.filterNot { it.roomId in savedIds }
        if (state.rooms.isNotEmpty()) Text("${state.rooms.size} ${if (state.rooms.size == 1) "conversation" else "conversations"} in your account",
            style = MaterialTheme.typography.bodySmall)
        if (remoteRooms.isNotEmpty()) {
            Text("From your other devices", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Find a chat or room") })
            val found = remoteRooms.filter { it.label.contains(query.trim(), true) }
            if (found.isEmpty()) Text("No chats or rooms match your search.")
            for (room in found) key(room.roomId) {
                ConversationRow(room.label, "Synced from your account", enabled, { actions.open(room) },
                    listOf(ConversationAction("Remove from account", "Remove ${room.label} from account", true) { removing = room }))
            }
        }
        TextButton({ help = !help }) { Text(if (help) "Hide sync help" else "Missing a conversation?") }
        if (help) Text("Use the same Nostr account and overlapping relays on both devices. In the browser, add any browser-only rooms to your account. Opening a restored room may need another member online; messages load from the available history.", style = MaterialTheme.typography.bodySmall)
    }
    removing?.let { room -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove ${room.label} from your account?") },
        text = { Text("This removes its bookmark from your synced account list on all devices. It does not delete messages, revoke access or erase rooms saved on this phone.") },
        confirmButton = { TextButton({ removing = null; actions.remove(room.roomId) }) { Text("Remove from account") } },
        dismissButton = { TextButton({ removing = null }) { Text("Cancel") } }) }
    if (importing) AlertDialog(onDismissRequest = { importing = false },
        title = { Text("Add this phone's rooms to your account?") },
        text = { Text("Names and invitation links for rooms joined with this account will be encrypted to your Nostr key and saved on your relays. Other devices using this account can then find them. Guest and anonymous rooms stay on this phone.") },
        confirmButton = { TextButton({ importing = false; actions.importRooms() }) { Text("Add rooms") } },
        dismissButton = { TextButton({ importing = false }) { Text("Cancel") } })
}
