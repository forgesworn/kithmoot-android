package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * The name field, the Tor row and the Start button: the whole of "starting
 * a room" in one reusable shape, used inline on the cold screen and the
 * expanded pane, and inside the New room sheet everywhere else.
 */
@Composable
internal fun NewRoomForm(
    roomName: String,
    onRoomNameChanged: (String) -> Unit,
    anonymousMode: Boolean,
    onAnonymousModeChanged: (Boolean) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    error: String?,
    onStartRoom: () -> Unit,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    nameFieldModifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = roomName, onValueChange = onRoomNameChanged,
            modifier = Modifier.fillMaxWidth().then(nameFieldModifier), enabled = enabled,
            label = { Text("Room name (optional)") }, placeholder = { Text("e.g. Saturday workshop") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (enabled) onStartRoom() }),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = anonymousMode, enabled = enabled, role = Role.Switch, onValueChange = onAnonymousModeChanged),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tor-only room (Orbot)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A new identity just for this room, over Tor only. No account, profiles, agents or calls.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = anonymousMode, onCheckedChange = null, enabled = enabled)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onStartRoom, enabled = enabled && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Start a room") }
            if (onCancel != null) TextButton(onCancel, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
