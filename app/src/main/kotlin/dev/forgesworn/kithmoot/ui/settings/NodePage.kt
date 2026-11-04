package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.forgesworn.kithmoot.node.NodeOperationRef
import dev.forgesworn.kithmoot.node.NodeSettingsView
import java.net.URI
import java.util.Locale

@Composable
internal fun NodePage(view: NodeSettingsView, onRefresh: () -> Unit, onUnpair: () -> Unit,
    onCheckOperation: (NodeOperationRef) -> Unit, onForgetOperation: (NodeOperationRef) -> Unit) {
    var unpairing by rememberSaveable { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<NodeOperationRef?>(null) }
    SettingsSection(null) {
        SettingsNote("Node rooms use your server for messages and calls, with files off. The server holds no room keys.")
        when {
            view.account == null -> SettingsNote("Sign in before pairing your KithMoot node.")
            view.pair == null -> SettingsNote("Install KithMoot node on your rented server or Archy, then open its Pair with KithMoot link.")
            else -> {
                SettingsStaticRow(URI(view.pair.origin).host, "Paired on this device", live = true)
                view.status?.let { status ->
                    SettingsStaticRow(if (status.ready) "Ready" else "Needs attention", "Version ${status.version}", live = true)
                    SettingsStaticRow("Room relay", if (status.relayReady) "Available" else "Unavailable")
                    SettingsStaticRow("Call relay", if (status.turnReady) "Available" else "Unavailable")
                    SettingsStaticRow("Rooms and devices", "${status.rooms} rooms · ${status.devices} devices · ${status.pending} pending closures")
                    SettingsStaticRow("Storage use", status.storeBytes?.let { String.format(Locale.ROOT, "%.1f MB", it / (1024.0 * 1024.0)) } ?: "Not reported")
                }
                SettingsActionRow(if (view.busy) "Checking…" else "Check node", enabled = !view.busy, onClick = onRefresh)
                SettingsActionRow("Unpair on this device", destructive = true, enabled = !view.busy) { unpairing = true }
            }
        }
        view.message?.let { SettingsNote(it, live = true) }
    }
    if (view.operations.isNotEmpty()) SettingsSection("Closures") {
        SettingsNote("These public operation references remain after the room's private data is deleted.")
        for (reference in view.operations) {
            SettingsStaticRow("Closure ${reference.operation.take(8)}", URI(reference.pair.origin).host)
            SettingsActionRow("Check closure ${reference.operation.take(8)}", enabled = !view.busy) { onCheckOperation(reference) }
            SettingsActionRow("Forget reference ${reference.operation.take(8)}", destructive = true, enabled = !view.busy) { forgetting = reference }
        }
    }
    if (unpairing) AlertDialog(onDismissRequest = { unpairing = false }, title = { Text("Unpair on this device?") },
        text = { Text("The saved pairing is removed from this device. Hosted rooms keep their existing access, and the node remains owned by your account.") },
        confirmButton = { TextButton(onClick = { unpairing = false; onUnpair() }) { Text("Unpair", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { unpairing = false }) { Text("Cancel") } })
    forgetting?.let { reference -> AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("Forget this closure reference?") },
        text = { Text("You will lose this device's reference for checking whether the node finished closing the room.") },
        confirmButton = { TextButton(onClick = { forgetting = null; onForgetOperation(reference) }) { Text("Forget reference", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } }) }
}
