package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.StartState

@Composable
fun StartScreen(
    state: StartState,
    onRoomNameChanged: (String) -> Unit,
    onJoinUrlChanged: (String) -> Unit,
    onRelaysChanged: (String) -> Unit,
    onStartRoom: () -> Unit,
    onJoin: () -> Unit,
    onReopen: (String) -> Unit,
    onForget: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRetryStorage: () -> Unit,
    onResetStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var relaysShown by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var renaming by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var renamed by remember { mutableStateOf("") }
    var resetting by remember { mutableStateOf(false) }
    val enabled = !state.busy && !state.loadingRooms && !state.storageError

    Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("KithMoot", style = MaterialTheme.typography.displaySmall)
                Text(if (state.savedRooms.isEmpty()) "Make room for a conversation." else "Pick up the conversation.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.busy || state.loadingRooms) {
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading rooms" })
            }
            if (state.storageError) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Saved rooms are unavailable", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text("Your saved data has been kept. Try again before deleting anything.")
                        OutlinedButton(onRetryStorage, enabled = !state.busy && !state.loadingRooms) { Text("Try again") }
                        TextButton({ resetting = true }, enabled = !state.busy && !state.loadingRooms) { Text("Delete saved rooms…") }
                    }
                }
            } else if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }

            if (state.savedRooms.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your rooms", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("Saved on this device. Reopen as the same person.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Find a saved room") },
                        trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("Clear") } })
                    val found = state.savedRooms.filter { it.name.contains(query.trim(), true) || it.id.contains(query.trim(), true) }
                    if (found.isEmpty()) Text("No rooms match your search.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    for (room in found) {
                        key(room.id) {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    TextButton({ onReopen(room.id) }, enabled = enabled,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(if (room.secondary) "Paired device" else "Main device",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton({ renaming = room; renamed = room.name }, enabled = enabled,
                                            modifier = Modifier.semantics { contentDescription = "Rename ${room.name}" }) { Text("Rename") }
                                        TextButton({ forgetting = room }, enabled = enabled,
                                            modifier = Modifier.semantics { contentDescription = "Forget ${room.name}" }) { Text("Forget") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Start a room", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(state.roomName, onRoomNameChanged, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("Room name (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (enabled) onStartRoom() }))
                    Button(onStartRoom, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start a room") }
                    Text("The name is yours to recognise this room on this device.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Have an invitation?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(state.joinUrl, onJoinUrlChanged, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("Invitation link") }, maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (enabled) onJoin() }))
                    OutlinedButton(onJoin, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Join room") }
                    Text("Only share invitations with people you want in the room. Your camera and microphone start off.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                TextButton({ relaysShown = !relaysShown }) { Text(if (relaysShown) "Hide relays" else "Relay settings") }
                if (relaysShown) {
                    OutlinedTextField(state.relays, onRelaysChanged, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("Relays, one per line") }, minLines = 2, maxLines = 5)
                    Text("Used for new rooms. Saved rooms keep their own relays.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Saved room access and identities are encrypted on this device and excluded from backups. " +
                "Room messages travel through relays encrypted. Forgetting a room does not delete those messages.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    forgetting?.let { room ->
        AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("Forget ${room.name}?") },
            text = { Text("Remove this room and your identity for it from this device. Creator controls saved here will be lost. " +
                "You will need an invitation or pairing link to return. Other members keep the room.") },
            confirmButton = { TextButton({ forgetting = null; onForget(room.id) }) { Text("Forget room") } },
            dismissButton = { TextButton({ forgetting = null }) { Text("Keep room") } })
    }
    renaming?.let { room ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Name on this device") },
            text = { OutlinedTextField(renamed, { renamed = it.take(80) }, label = { Text("Room name") }, singleLine = true) },
            confirmButton = { TextButton({ renaming = null; onRename(room.id, renamed) }, enabled = renamed.isNotBlank()) { Text("Save name") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    if (resetting) {
        AlertDialog(onDismissRequest = { resetting = false }, title = { Text("Delete all saved rooms?") },
            text = { Text("Permanently remove every saved room and identity from this device. You may lose access to rooms you created. " +
                "Other members and relay messages are unaffected. This cannot be undone.") },
            confirmButton = { TextButton({ resetting = false; onResetStorage() }) { Text("Delete saved rooms") } },
            dismissButton = { TextButton({ resetting = false }) { Text("Keep saved data") } })
    }
}
