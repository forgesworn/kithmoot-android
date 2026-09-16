package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class ConversationAction(val label: String, val description: String, val destructive: Boolean = false, val run: () -> Unit)

@Composable
internal fun ConversationRow(name: String, detail: String, enabled: Boolean, open: () -> Unit, actions: List<ConversationAction>) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(open, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(open, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Open $name" }) { Text("Open") }
            Box {
                IconButton({ menu = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, "Options for $name") }
                DropdownMenu(menu, { menu = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(text = { Text(action.label, color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                            onClick = { menu = false; action.run() }, modifier = Modifier.semantics { contentDescription = action.description })
                    }
                }
            }
        }
    }
}
