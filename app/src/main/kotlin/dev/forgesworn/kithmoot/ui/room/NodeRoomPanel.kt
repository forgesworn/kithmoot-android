package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.node.NodeHostedRoomView

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NodeAdmissionPanel(
    view: NodeHostedRoomView,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
    onAnswer: (String, Boolean) -> Unit,
) {
    if (!view.keeper || view.requests.isEmpty()) return
    Column(modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Waiting to join (${view.requests.size})", Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleSmall)
        for (request in view.requests) key(request.id) {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${request.name ?: "Guest"} wants to join", style = MaterialTheme.typography.titleSmall)
                    Text("Check who this is before admitting them.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onAnswer(request.id, true) }, enabled = !view.busy && !view.pendingClose) { Text("Admit") }
                        TextButton(onClick = { onAnswer(request.id, false) }, enabled = !view.busy) { Text("Decline") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NodeJoinDialog(waiting: Boolean, busy: Boolean, host: String, message: String?, onAsk: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(when {
                waiting -> "Waiting to be admitted"
                busy -> "Opening the room…"
                else -> "Ask to join this room?"
            }, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    waiting -> {
                        Text("The person who manages the room can let you in.")
                        Text("Keep KithMoot open for their reply. The request waits up to ten minutes.")
                    }
                    busy -> Text("Checking room access.")
                    else -> {
                        Text("$host carries this room's messages and calls.")
                        Text("The link lets you ask to join. The person who manages the room decides who to admit.")
                        Text("Files are off. Your room data self-destructs if you are removed or the room closes.")
                    }
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = { if (!busy && !waiting) TextButton(onClick = onAsk) { Text("Ask to join") } },
        dismissButton = { TextButton(onClick = onCancel) { Text(if (waiting) "Stop asking" else "Cancel") } },
    )
}

@Composable
internal fun NodeRoomPanel(view: NodeHostedRoomView, onRemove: (String) -> Unit, onClose: () -> Unit) {
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var closing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hosted on", style = MaterialTheme.typography.titleSmall)
        Text(view.host)
        Text(if(view.existingRoom)"Messages and calls go through this node. This room keeps its existing file settings. Room keys stay on the devices." else "Messages and calls go through this node. Files are off. Room keys stay on the devices.", style = MaterialTheme.typography.bodySmall)
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
        text = { Text(if(view.destruct)"Their devices lose message and call access and erase this room. The remaining members receive a new room key." else "Their devices lose message and call access. Their saved history remains. The remaining members receive a new room key.") },
        confirmButton = { TextButton(onClick = { removing = null; onRemove(person.first) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
    if (closing) AlertDialog(onDismissRequest = { closing = false }, title = { Text("Close this room?") },
        text = { Text("The node revokes access, ends calls and deletes the room's stored messages. Every device erases its copy. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { closing = false; onClose() }) { Text("Close and delete") } },
        dismissButton = { TextButton(onClick = { closing = false }) { Text("Cancel") } })
}
