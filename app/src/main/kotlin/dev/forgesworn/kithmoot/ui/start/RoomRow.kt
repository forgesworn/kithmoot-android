package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A menu item on a row's overflow: a visible label the accessible name
 *  never differs from (F6), and whether it reads in the destructive colour. */
internal data class ConversationAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

/**
 * One row of the merged home rooms list (design-home-rooms.md section 5): no
 * card, no border, no divider - rows are separated by their own padding, and
 * the whole open area is one merged accessibility node so TalkBack reads the
 * name, the status line, then the time, then "Double-tap to open, double-tap
 * and hold for more options".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RoomRow(name: String, status: String?, time: String, timeSpoken: String, enabled: Boolean,
    open: () -> Unit, actions: List<ConversationAction>) {
    var menu by remember { mutableStateOf(false) }
    val stacked = LocalDensity.current.fontScale >= 1.5f
    // A row with only a name centres it beside the menu at the one-line list
    // height; a status line makes it two-line and the menu stays at the top.
    val plain = status == null && !stacked
    Row(Modifier.fillMaxWidth(), verticalAlignment = if (plain) Alignment.CenterVertically else Alignment.Top) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = if (plain) 56.dp else 64.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = open, onClickLabel = "Open",
                    onLongClick = { menu = true }, onLongClickLabel = "More options",
                )
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            if (stacked) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                RowTime(time, timeSpoken)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Box(Modifier.wrapContentWidth()) { RowTime(time, timeSpoken) }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            }
        }
        Box {
            IconButton({ menu = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.MoreVert, "More options for $name")
            }
            DropdownMenu(menu, { menu = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label, color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                        onClick = { menu = false; action.run() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowTime(time: String, timeSpoken: String) {
    Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = timeSpoken })
}
