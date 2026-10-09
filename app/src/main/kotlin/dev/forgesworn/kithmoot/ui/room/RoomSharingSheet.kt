package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.RoomSharingState

@Composable
internal fun RoomSharingSheet(state: RoomSharingState, onSelect: (String, Boolean) -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share connection") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Carry these people's room chat between nearby devices and this room's Internet connections while KithMoot is open.")
                Text(if (state.enabled) "Sharing is on" else "Sharing is off", style = MaterialTheme.typography.titleSmall)
                Text("Sharing stops when you leave the room or put the app in the background. Reopening requires Resume sharing. Sending does not confirm that another person received a message.")
                Text("Internet connections", style = MaterialTheme.typography.labelLarge)
                state.relays.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Choose up to 32 people. Nobody is approved automatically.")
                if (state.candidates.isEmpty()) Text("People appear here when they join or send room chat.")
                state.candidates.forEach { key ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(key in state.selected, { onSelect(key, it) },
                            enabled = !state.busy && (key in state.selected || state.selected.size < 32))
                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                            Text(shortId(key))
                            Text(key, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Changing people or Internet connections stops sharing and retires the previous queued exports.",
                    style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (state.enabled) TextButton(onClick = onStop) { Text("Stop sharing") }
            else TextButton(onClick = onStart, enabled = !state.busy && state.selected.isNotEmpty()) {
                Text(if (state.previouslySaved) "Resume sharing" else "Start sharing")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
