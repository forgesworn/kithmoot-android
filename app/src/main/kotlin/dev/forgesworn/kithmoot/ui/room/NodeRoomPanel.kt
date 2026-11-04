package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.node.NodeHostedRoomView

@Composable
internal fun NodeAdmissionPanel(view: NodeHostedRoomView, onAnswer: (String, Boolean) -> Unit) {
    if (!view.keeper || view.requests.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Waiting to join (${view.requests.size})", style = MaterialTheme.typography.titleSmall)
        for (request in view.requests) key(request.id) {
            Text(request.name ?: "Guest", style = MaterialTheme.typography.bodyMedium)
            Text("Check who this is before admitting them.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAnswer(request.id, true) }, enabled = !view.busy && !view.pendingClose) { Text("Admit") }
                TextButton(onClick = { onAnswer(request.id, false) }, enabled = !view.busy) { Text("Decline") }
            }
        }
    }
}

@Composable
internal fun NodeRoomPanel(view: NodeHostedRoomView, onRemove: (String) -> Unit, onClose: () -> Unit) {
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var closing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hosted on", style = MaterialTheme.typography.titleSmall)
        Text(view.host)
        Text("Messages and calls go through this node. Files are off. Room keys stay on the devices.", style = MaterialTheme.typography.bodySmall)
        view.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (view.keeper) {
            for ((participant, label) in view.members) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, Modifier.weight(1f))
                TextButton(onClick = { removing = participant to label }, enabled = !view.busy && !view.pendingClose) { Text("Remove") }
            }
            TextButton(onClick = { closing = true }, enabled = !view.busy) { Text(if (view.pendingClose) "Retry room closure" else "Close room and delete everything") }
        }
    }
    removing?.let { person -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${person.second}?") },
        text = { Text("Their devices lose message and call access and erase this room. The remaining members receive a new room key.") },
        confirmButton = { TextButton(onClick = { removing = null; onRemove(person.first) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
    if (closing) AlertDialog(onDismissRequest = { closing = false }, title = { Text("Close this room?") },
        text = { Text("The node revokes access, ends calls and deletes the room's stored messages. Every device erases its copy. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { closing = false; onClose() }) { Text("Close and delete") } },
        dismissButton = { TextButton(onClick = { closing = false }) { Text("Cancel") } })
}
